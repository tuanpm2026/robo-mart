package com.robomart.inventory.exception;

/**
 * Thrown when a reserve arrives for an order whose reservation was already released (including a
 * release tombstone recorded because the release arrived before any reservation existed). Stock
 * must not be reserved for that order again.
 */
public class ReservationReleasedException extends RuntimeException {

    private final String orderId;

    public ReservationReleasedException(String orderId) {
        super("Reservation already released for order " + orderId);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}
