package com.robomart.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.robomart.common.dto.ApiResponse;
import com.robomart.inventory.client.OrderStatusClient;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.event.producer.InventoryEventProducer;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.service.InventoryService;
import com.robomart.inventory.service.ReservationExpiryJob;
import com.robomart.test.PostgresContainerConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "robomart.inventory.expiry-enabled=true", "robomart.inventory.expiry-interval=PT1H"})
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
class ReservationExpiryIT {
    private static final AtomicLong PRODUCTS = new AtomicLong(9000000);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryService inventory;
    @Autowired private InventoryItemRepository items;
    @Autowired private ReservationExpiryJob job;
    @MockitoBean private OrderStatusClient orders;
    @MockitoBean private InventoryEventProducer producer;

    private String expired(String status) {
        InventoryItem item = new InventoryItem();
        item.setProductId(PRODUCTS.incrementAndGet());
        item.setAvailableQuantity(10);
        item.setTotalQuantity(10);
        item.setReservedQuantity(0);
        item.setLowStockThreshold(1);
        items.saveAndFlush(item);
        String id = UUID.randomUUID().toString();
        inventory.reserveOrder(id, Map.of(item.getProductId(), 2));
        jdbc.update("UPDATE reservations SET expires_at=now()-interval '1 hour' WHERE order_id=?", id);
        when(orders.status(id)).thenReturn(new ApiResponse<>(status, null));
        return id;
    }

    // Exercise reconciliation independently of the scheduler's minimum lock duration.
    private void sweepDirectly() {
        ReservationExpiryJob target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(job);
        target.sweep();
    }

    private String reservationStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE order_id=?", String.class, id);
    }

    @Test
    void confirmedIsCommittedAndCancelledIsReleasedUsingStoredLines() {
        String confirmed = expired("CONFIRMED");
        String cancelled = expired("CANCELLED");
        sweepDirectly();
        assertThat(reservationStatus(confirmed)).isEqualTo("COMMITTED");
        assertThat(reservationStatus(cancelled)).isEqualTo("RELEASED");
        sweepDirectly();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE order_id=? AND type='RELEASE'",
                Integer.class, cancelled)).isEqualTo(1);
    }

    @Test
    void uncertainOrUnavailableOrderHoldsStockAndFlagsReviewOnce() {
        String pending = expired("PAYMENT_PENDING");
        String unavailable = expired("CANCELLED");
        when(orders.status(unavailable)).thenThrow(new IllegalStateException("order unavailable"));
        sweepDirectly();
        for (String id : new String[]{pending, unavailable}) {
            assertThat(reservationStatus(id)).isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT expiry_review_at IS NOT NULL AND expiry_next_check_at>now() "
                    + "FROM reservations WHERE order_id=?", Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE order_id=? AND type='RELEASE'",
                    Integer.class, id)).isZero();
        }
    }
}
