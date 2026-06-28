package com.robomart.order.enums;

public enum OrderStatus {
    PENDING,
    PAYMENT_PENDING,
    INVENTORY_RESERVING,
    PAYMENT_PROCESSING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    PAYMENT_REFUNDING,
    INVENTORY_RELEASING,
    // Non-terminal: a cancellation could not refund the customer. The order is held here (NOT
    // CANCELLED — money is still owed) so the recovery scan keeps retrying the refund. Reaching
    // CANCELLED requires a successful (or unnecessary) refund.
    REFUND_FAILED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == DELIVERED;
    }
}
