package com.robomart.inventory.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deduplication and reservation changes share the same database transaction. */
@Service
public class OrderLifecycleService {
    private static final String GROUP = "inventory-order-lifecycle";
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;

    public OrderLifecycleService(JdbcTemplate jdbc, InventoryService inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    @Transactional
    public void handle(String eventId, String orderId, String status) {
        if (eventId == null || eventId.isBlank() || orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Order lifecycle event requires eventId and orderId");
        }
        if (!"CONFIRMED".equals(status) && !"CANCELLED".equals(status)) {
            return;
        }
        int inserted = jdbc.update("INSERT INTO processed_events(consumer_group, event_id) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", GROUP, eventId);
        if (inserted == 0) {
            return;
        }
        if ("CONFIRMED".equals(status)) {
            inventory.commitReservation(orderId);
        } else {
            inventory.releaseReservation(orderId);
        }
    }
}
