package com.robomart.order.grpc;

public class InventoryServiceUnavailableException extends RuntimeException {
    public InventoryServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
