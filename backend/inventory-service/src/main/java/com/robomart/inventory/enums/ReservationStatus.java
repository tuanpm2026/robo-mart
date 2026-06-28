package com.robomart.inventory.enums;

/**
 * Lifecycle of an order's inventory reservation. Tracked per {@code orderId} to make the gRPC
 * reserve/release calls idempotent (a saga-step timeout or retry must not double-reserve or leak
 * stock).
 */
public enum ReservationStatus {
    RESERVED,
    RELEASED
}
