package com.robomart.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.entity.OutboxEvent;
import com.robomart.inventory.entity.StockMovement;
import com.robomart.inventory.enums.StockMovementType;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.OutboxEventRepository;
import com.robomart.inventory.repository.ReservationLineRepository;
import com.robomart.inventory.repository.ReservationRepository;
import com.robomart.inventory.repository.StockMovementRepository;
import com.robomart.inventory.service.InventoryService;
import com.robomart.test.IntegrationTest;

/**
 * Integration tests for {@link InventoryService} with real PostgreSQL via Testcontainers.
 *
 * <p>These tests verify end-to-end behavior of stock reservation and release operations,
 * concurrency safety, audit trail creation, and outbox event publishing.
 *
 * <p>Seed data provides ~50 inventory items (product IDs 1-50). Tests use various product IDs
 * to avoid interference between tests.
 */
@IntegrationTest
class InventoryServiceIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationLineRepository reservationLineRepository;

    @Test
    void shouldReserveAndReleaseStockEndToEnd() {
        // Arrange: get an existing inventory item from seed data (product_id=1, available=250)
        Long productId = 1L;
        InventoryItem original = inventoryItemRepository.findByProductId(productId).orElseThrow();
        int originalAvailable = original.getAvailableQuantity();
        int originalReserved = original.getReservedQuantity();

        // Act: reserve 2 units
        inventoryService.reserveOrder("test-order-1", Map.of(productId, 2));
        InventoryItem afterReserve = inventoryService.getInventory(productId);

        // Assert: available decreased by 2, reserved increased by 2
        assertThat(afterReserve.getAvailableQuantity()).isEqualTo(originalAvailable - 2);
        assertThat(afterReserve.getReservedQuantity()).isEqualTo(originalReserved + 2);

        // Act: release 2 units
        inventoryService.releaseReservation("test-order-1");
        InventoryItem afterRelease = inventoryService.getInventory(productId);

        // Assert: quantities restored to original
        assertThat(afterRelease.getAvailableQuantity()).isEqualTo(originalAvailable);
        assertThat(afterRelease.getReservedQuantity()).isEqualTo(originalReserved);
    }

    @Test
    void shouldPreventOversellWithConcurrentReservations() throws InterruptedException {
        // Arrange: set product available=10 for a controlled concurrency test
        Long productId = 2L;
        InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
        item.setAvailableQuantity(10);
        item.setReservedQuantity(0);
        item.setTotalQuantity(10);
        inventoryItemRepository.save(item);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        // Act: spin up 20 threads each trying to reserve 1 unit concurrently
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    inventoryService.reserveOrder("concurrent-order-" + index, Map.of(productId, 1));
                    successes.incrementAndGet();
                } catch (InsufficientStockException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads at once
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Assert: at most 10 reservations succeeded (no oversell)
        assertThat(successes.get()).isEqualTo(10);
        assertThat(failures.get()).isEqualTo(10);
        assertThat(successes.get() + failures.get()).isEqualTo(threadCount);

        // Conservation: every successful reservation moved exactly one unit, failures moved none
        InventoryItem refreshed = inventoryItemRepository.findByProductId(productId).orElseThrow();
        assertThat(refreshed.getAvailableQuantity()).isEqualTo(10 - successes.get());
        assertThat(refreshed.getReservedQuantity()).isEqualTo(successes.get());
    }

    @Test
    void shouldConserveStockWithConcurrentOrderReservations() throws InterruptedException {
        // Arrange: two products, 10 units each; every order reserves one of each
        Long productA = 9L;
        Long productB = 10L;
        for (Long productId : List.of(productA, productB)) {
            InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
            item.setAvailableQuantity(10);
            item.setReservedQuantity(0);
            inventoryItemRepository.save(item);
        }

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<String> reservedOrders = new CopyOnWriteArrayList<>();

        // Act: 20 orders reserve concurrently (lock contention makes some fail fast)
        for (int i = 0; i < threadCount; i++) {
            final String orderId = "concurrent-multi-order-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    inventoryService.reserveOrder(orderId, Map.of(productA, 1, productB, 1));
                    reservedOrders.add(orderId);
                } catch (Exception e) {
                    // insufficient stock / lock contention: must leave no trace
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Assert: conservation on both products, and a reservation + lines only for the winners
        int successes = reservedOrders.size();
        assertThat(successes).isLessThanOrEqualTo(10);
        for (Long productId : List.of(productA, productB)) {
            InventoryItem refreshed = inventoryItemRepository.findByProductId(productId).orElseThrow();
            assertThat(refreshed.getAvailableQuantity()).isEqualTo(10 - successes);
            assertThat(refreshed.getReservedQuantity()).isEqualTo(successes);
        }
        for (int i = 0; i < threadCount; i++) {
            String orderId = "concurrent-multi-order-" + i;
            boolean won = reservedOrders.contains(orderId);
            assertThat(reservationRepository.findByOrderId(orderId).isPresent()).isEqualTo(won);
            assertThat(reservationLineRepository.findByOrderIdOrderByProductIdAsc(orderId)).hasSize(won ? 2 : 0);
        }

        // Releasing every winner restores the initial stock exactly
        reservedOrders.forEach(orderId -> assertThat(inventoryService.releaseReservation(orderId))
                .isEqualTo(InventoryService.ReleaseOutcome.RELEASED));
        for (Long productId : List.of(productA, productB)) {
            InventoryItem refreshed = inventoryItemRepository.findByProductId(productId).orElseThrow();
            assertThat(refreshed.getAvailableQuantity()).isEqualTo(10);
            assertThat(refreshed.getReservedQuantity()).isZero();
        }
    }

    @Test
    void shouldCreateStockMovementAuditTrail() {
        // Arrange: use product_id=3
        Long productId = 3L;

        // Act: reserve stock
        inventoryService.reserveOrder("audit-trail-order", Map.of(productId, 1));

        // Assert: at least one stock movement with type RESERVE exists for this item
        InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
        List<StockMovement> movements = stockMovementRepository
                .findByInventoryItemIdOrderByCreatedAtDesc(item.getId());

        assertThat(movements).isNotEmpty();
        assertThat(movements)
                .anyMatch(m -> m.getType() == StockMovementType.RESERVE
                        && "audit-trail-order".equals(m.getOrderId()));
    }

    @Test
    void shouldPublishOutboxEventsOnStockChange() {
        // Arrange: use product_id=4
        Long productId = 4L;

        // Act: reserve stock
        inventoryService.reserveOrder("outbox-test-order", Map.of(productId, 1));

        // Publication may race this assertion; the durable outbox row must exist either way.
        List<OutboxEvent> events = outboxEventRepository.findAll();

        assertThat(events).isNotEmpty();
        assertThat(events)
                .anyMatch(e -> "stock_reserved".equals(e.getEventType())
                        && productId.toString().equals(e.getAggregateId()));
    }

    @Test
    void shouldThrowInsufficientStockWhenNotEnoughAvailable() {
        // Arrange: product_id=49 has available_quantity=0 in seed data
        Long productId = 49L;

        // Act & Assert: try to reserve more than available
        assertThatThrownBy(() -> inventoryService.reserveOrder("insufficient-order", Map.of(productId, 1)))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void shouldThrowResourceNotFoundWhenProductNotExists() {
        // Act & Assert: non-existent product ID
        assertThatThrownBy(() -> inventoryService.reserveOrder("not-found-order", Map.of(99999L, 1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }    private long stockFixture(int available) {
        long productId = 900000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900000);
        InventoryItem item = new InventoryItem();
        item.setProductId(productId);
        item.setAvailableQuantity(available);
        item.setReservedQuantity(0);
        item.setTotalQuantity(available);
        item.setLowStockThreshold(10);
        inventoryItemRepository.saveAndFlush(item);
        return productId;
    }

    @Test
    void laterInsufficientLineRollsBackReservationStockMovementsAndEvents() {
        long first = stockFixture(20);
        long second = stockFixture(1);
        long lower = Math.min(first, second);
        long higher = Math.max(first, second);
        jdbc.update("UPDATE inventory_items SET available_quantity=20,total_quantity=20 WHERE product_id=?", lower);
        jdbc.update("UPDATE inventory_items SET available_quantity=1,total_quantity=1 WHERE product_id=?", higher);
        String order = "atomic-rollback-" + java.util.UUID.randomUUID();
        assertThatThrownBy(() -> inventoryService.reserveOrder(order, Map.of(lower, 2, higher, 2)))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(inventoryService.getInventory(lower).getAvailableQuantity()).isEqualTo(20);
        assertThat(reservationRepository.findByOrderId(order)).isEmpty();
        assertThat(reservationLineRepository.findByOrderIdOrderByProductIdAsc(order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE order_id=?", Integer.class, order))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE payload->>'orderId'=?",
                Integer.class, order)).isZero();
    }

    @Test
    void lowStockIsEmittedOnlyOnThresholdCrossing() {
        long product = stockFixture(11);
        inventoryService.reserveOrder("crossing-" + java.util.UUID.randomUUID(), Map.of(product, 2));
        inventoryService.reserveOrder("below-" + java.util.UUID.randomUUID(), Map.of(product, 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? "
                + "AND event_type='stock_low_alert'", Integer.class, Long.toString(product))).isEqualTo(1);
    }

    @Test
    void alertWriteFailureRollsBackTheWholeReservation() {
        long product = stockFixture(11);
        String order = "alert-rollback-" + java.util.UUID.randomUUID();
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_test_alert CHECK "
                + "(event_type <> 'stock_low_alert' OR aggregate_id <> '" + product + "') NOT VALID");
        try {
            assertThatThrownBy(() -> inventoryService.reserveOrder(order, Map.of(product, 2)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(inventoryService.getInventory(product).getAvailableQuantity()).isEqualTo(11);
            assertThat(reservationRepository.findByOrderId(order)).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_test_alert");
        }
    }

    @Test
    void databaseRejectsNegativeAvailableAndReservedQuantities() {
        long product = stockFixture(10);
        assertThatThrownBy(() -> jdbc.update("UPDATE inventory_items SET available_quantity=-1 WHERE product_id=?", product))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE inventory_items SET reserved_quantity=-1 WHERE product_id=?", product))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

}
