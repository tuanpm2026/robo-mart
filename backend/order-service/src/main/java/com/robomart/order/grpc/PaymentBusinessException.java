package com.robomart.order.grpc;

import io.grpc.StatusRuntimeException;

/**
 * Wraps a deterministic business rejection from the Payment service (gRPC
 * {@code FAILED_PRECONDITION}, e.g. payment declined).
 *
 * <p>This is a distinct type from {@link StatusRuntimeException} on purpose: business rejections
 * must NOT be retried and must NOT trip the circuit breaker (retrying a deterministic decline
 * wastes time and produces the wrong saga outcome). It is therefore listed under
 * {@code resilience4j.*.instances.payment-service.ignore-exceptions} so Resilience4j neither
 * retries it nor counts it as a circuit-breaker failure, and it propagates straight through the
 * client wrapper to the saga step for correct handling.
 */
public class PaymentBusinessException extends RuntimeException {

    public PaymentBusinessException(String message, StatusRuntimeException cause) {
        super(message, cause);
    }
}
