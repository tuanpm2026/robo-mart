package com.robomart.inventory.service;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.robomart.inventory.client.OrderStatusClient;

/** Bounded reconciliation outside database transactions; uncertain payment never releases stock. */
@Component
@ConditionalOnProperty(prefix = "robomart.inventory", name = "expiry-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryJob.class);
    private final JdbcTemplate jdbc;
    private final OrderStatusClient orders;
    private final InventoryService inventory;
    private final MeterRegistry meters;

    public ReservationExpiryJob(JdbcTemplate jdbc, OrderStatusClient orders, InventoryService inventory,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.inventory = inventory;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${robomart.inventory.expiry-interval:PT1M}")
    @SchedulerLock(name = "inventory-reservation-expiry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void sweep() {
        List<String> due = jdbc.queryForList("SELECT order_id FROM reservations WHERE status='RESERVED' "
                + "AND COALESCE(expiry_next_check_at, expires_at)<now() "
                + "ORDER BY COALESCE(expiry_next_check_at, expires_at), id LIMIT 10", String.class);
        for (String order : due) {
            try {
                String status = orders.status(order).data();
                if ("CANCELLED".equals(status)) {
                    inventory.releaseReservation(order);
                } else if ("CONFIRMED".equals(status) || "SHIPPED".equals(status) || "DELIVERED".equals(status)) {
                    inventory.commitReservation(order);
                } else {
                    hold(order, "non-terminal order status");
                }
            } catch (Exception failure) {
                hold(order, failure.getClass().getSimpleName());
            }
        }
    }

    private void hold(String order, String reason) {
        int firstReview = jdbc.update("UPDATE reservations SET expiry_review_at=now() "
                + "WHERE order_id=? AND status='RESERVED' AND expiry_review_at IS NULL", order);
        jdbc.update("UPDATE reservations SET expiry_next_check_at=now()+interval '5 minutes' "
                + "WHERE order_id=? AND status='RESERVED'", order);
        if (firstReview != 0) {
            meters.counter("inventory.reservation.expiry.manual_review").increment();
            LOG.error("Expired reservation requires manual review; stock held: orderId={}, reason={}", order, reason);
        }
    }
}
