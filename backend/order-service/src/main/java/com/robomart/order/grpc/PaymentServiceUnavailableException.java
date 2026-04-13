package com.robomart.order.grpc;

public class PaymentServiceUnavailableException extends RuntimeException {
    public PaymentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
