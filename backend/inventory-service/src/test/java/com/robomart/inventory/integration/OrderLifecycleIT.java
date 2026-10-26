package com.robomart.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.robomart.inventory.config.InventoryProperties;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.enums.ReservationStatus;
import com.robomart.inventory.event.producer.InventoryEventProducer;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.ReservationRepository;
import com.robomart.inventory.service.InventoryService;
import com.robomart.inventory.service.OrderLifecycleService;
import com.robomart.test.PostgresContainerConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.validate-group-membership=false")
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
class OrderLifecycleIT {
    private static final AtomicLong PRODUCTS = new AtomicLong(2000000);
    @Autowired private OrderLifecycleService lifecycle;
    @Autowired private InventoryService inventory;
    @Autowired private InventoryItemRepository items;
    @Autowired private ReservationRepository reservations;
    @Autowired private InventoryProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private InventoryEventProducer producer;

    private long product() {
        InventoryItem item = new InventoryItem();
        item.setProductId(PRODUCTS.incrementAndGet());
        item.setAvailableQuantity(10);
        item.setReservedQuantity(0);
        item.setTotalQuantity(10);
        item.setLowStockThreshold(5);
        return items.saveAndFlush(item).getProductId();
    }

    private void assertStock(long product, int available, int reserved, int total) {
        InventoryItem item = inventory.getInventory(product);
        assertThat(item.getAvailableQuantity()).isEqualTo(available);
        assertThat(item.getReservedQuantity()).isEqualTo(reserved);
        assertThat(item.getTotalQuantity()).isEqualTo(total);
    }

    @Test
    void confirmationConsumesReservedUnitsOnceAndCancellationReturnsCommittedStockOnce() {
        long product = product();
        String order = UUID.randomUUID().toString();
        inventory.reserveOrder(order, Map.of(product, 2));
        String confirmation = UUID.randomUUID().toString();
        lifecycle.handle(confirmation, order, "CONFIRMED");
        lifecycle.handle(confirmation, order, "CONFIRMED");
        lifecycle.handle(UUID.randomUUID().toString(), order, "CONFIRMED");
        assertStock(product, 8, 0, 8);
        assertThat(reservations.findByOrderId(order).orElseThrow().getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE order_id=? AND type='COMMIT'",
                Integer.class, order)).isEqualTo(1);

        lifecycle.handle(UUID.randomUUID().toString(), order, "CANCELLED");
        lifecycle.handle(UUID.randomUUID().toString(), order, "CANCELLED");
        lifecycle.handle(UUID.randomUUID().toString(), order, "CONFIRMED");
        assertStock(product, 10, 0, 10);
        assertThat(reservations.findByOrderId(order).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void cancellationBeforeConfirmationNeverResurrectsReservation() {
        long product = product();
        String order = UUID.randomUUID().toString();
        inventory.reserveOrder(order, Map.of(product, 2));
        lifecycle.handle(UUID.randomUUID().toString(), order, "CANCELLED");
        lifecycle.handle(UUID.randomUUID().toString(), order, "CONFIRMED");
        assertStock(product, 10, 0, 10);
    }

    @Test
    void failedCommitRollsBackStockStatusAndDedupThenSameEventCanRetry() {
        long first = product();
        long second = product();
        String order = UUID.randomUUID().toString();
        String event = UUID.randomUUID().toString();
        inventory.reserveOrder(order, Map.of(first, 2, second, 2));
        jdbc.update("UPDATE inventory_items SET reserved_quantity=0 WHERE product_id=?", second);
        assertThatThrownBy(() -> lifecycle.handle(event, order, "CONFIRMED")).isInstanceOf(IllegalStateException.class);
        assertStock(first, 8, 2, 10);
        assertThat(reservations.findByOrderId(order).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?", Integer.class, event))
                .isZero();
        jdbc.update("UPDATE inventory_items SET reserved_quantity=2 WHERE product_id=?", second);
        lifecycle.handle(event, order, "CONFIRMED");
        assertStock(first, 8, 0, 8);
        assertStock(second, 8, 0, 8);
    }

    @Test
    void concurrentEventRedeliveryCommitsOnlyOnce() throws Exception {
        long product = product();
        String order = UUID.randomUUID().toString();
        String event = UUID.randomUUID().toString();
        inventory.reserveOrder(order, Map.of(product, 2));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                work.add(pool.submit(() -> {
                    start.await();
                    lifecycle.handle(event, order, "CONFIRMED");
                    return null;
                }));
            }
            start.countDown();
            for (var task : work) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        assertStock(product, 8, 0, 8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?", Integer.class, event))
                .isEqualTo(1);
    }

    @Test
    void commitRacingReleaseAlwaysReturnsStockWithoutDoubleMutation() throws Exception {
        long product = product();
        String order = UUID.randomUUID().toString();
        inventory.reserveOrder(order, Map.of(product, 2));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var commit = pool.submit(() -> {
                start.await();
                inventory.commitReservation(order);
                return null;
            });
            var release = pool.submit(() -> {
                start.await();
                inventory.releaseReservation(order);
                return null;
            });
            start.countDown();
            commit.get(10, TimeUnit.SECONDS);
            release.get(10, TimeUnit.SECONDS);
        }
        assertStock(product, 10, 0, 10);
        assertThat(reservations.findByOrderId(order).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void configuredReservationExpiryIsPersistedAndNotRenewedOnReplay() {
        long product = product();
        String order = UUID.randomUUID().toString();
        Duration previous = properties.getReservationTtl();
        try {
            properties.setReservationTtl(Duration.ofMinutes(15));
            Instant before = Instant.now();
            inventory.reserveOrder(order, Map.of(product, 2));
            Instant expiry = reservations.findByOrderId(order).orElseThrow().getExpiresAt();
            assertThat(expiry).isBetween(before.plusSeconds(899), Instant.now().plusSeconds(901));
            inventory.reserveOrder(order, Map.of(product, 2));
            assertThat(reservations.findByOrderId(order).orElseThrow().getExpiresAt()).isEqualTo(expiry);
        } finally {
            properties.setReservationTtl(previous);
        }
    }
}
