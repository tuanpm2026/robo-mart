package com.robomart.inventory.integration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.config.RedisLockConfig;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.entity.OutboxEvent;
import com.robomart.inventory.entity.StockMovement;
import com.robomart.inventory.enums.StockMovementType;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.exception.LockAcquisitionException;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.OutboxEventRepository;
import com.robomart.inventory.repository.ReservationLineRepository;
import com.robomart.inventory.repository.ReservationRepository;
import com.robomart.inventory.repository.StockMovementRepository;
import com.robomart.inventory.service.InventoryService;
import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link InventoryService} with real PostgreSQL and Redis via Testcontainers.
 *
 * <p>These tests verify end-to-end behavior of stock reservation and release operations,
 * concurrency safety, audit trail creation, and outbox event publishing.
 *
 * <p>Seed data provides ~50 inventory items (product IDs 1-50). Tests use various product IDs
 * to avoid interference between tests, and each test cleans up Redis lock keys beforehand.
 */
@IntegrationTest
class InventoryServiceIT {

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void cleanUpRedisLocks() {
        // Clean up any leftover Redis lock keys from previous test runs
        Set<String> lockKeys = stringRedisTemplate.keys(RedisLockConfig.LOCK_KEY_PREFIX + "*");
        if (lockKeys != null && !lockKeys.isEmpty()) {
            stringRedisTemplate.delete(lockKeys);
        }
    }

    @Test
    void shouldReserveAndReleaseStockEndToEnd() {
        // Arrange: get an existing inventory item from seed data (product_id=1, available=250)
        Long productId = 1L;
        InventoryItem original = inventoryItemRepository.findByProductId(productId).orElseThrow();
        int originalAvailable = original.getAvailableQuantity();
        int originalReserved = original.getReservedQuantity();

        // Act: reserve 2 units
        InventoryItem afterReserve = inventoryService.reserveStock(productId, 2, "test-order-1");

        // Assert: available decreased by 2, reserved increased by 2
        assertThat(afterReserve.getAvailableQuantity()).isEqualTo(originalAvailable - 2);
        assertThat(afterReserve.getReservedQuantity()).isEqualTo(originalReserved + 2);

        // Act: release 2 units
        InventoryItem afterRelease = inventoryService.releaseStock(productId, 2, "test-order-1");

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
                    inventoryService.reserveStock(productId, 1, "concurrent-order-" + index);
                    successes.incrementAndGet();
                } catch (InsufficientStockException | LockAcquisitionException e) {
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
        assertThat(successes.get()).isLessThanOrEqualTo(10);
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
        inventoryService.reserveStock(productId, 1, "audit-trail-order");

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
        inventoryService.reserveStock(productId, 1, "outbox-test-order");

        // Assert: at least one unpublished outbox event with eventType "stock_reserved"
        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

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
        assertThatThrownBy(() -> inventoryService.reserveStock(productId, 1, "insufficient-order"))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void shouldThrowResourceNotFoundWhenProductNotExists() {
        // Act & Assert: non-existent product ID
        assertThatThrownBy(() -> inventoryService.reserveStock(99999L, 1, "not-found-order"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
