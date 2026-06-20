package com.robomart.order.grpc;

import io.grpc.StatusRuntimeException;

/**
 * Wraps a deterministic business rejection from the Inventory service (gRPC
 * {@code FAILED_PRECONDITION}, e.g. insufficient stock).
 *
 * <p>This is a distinct type from {@link StatusRuntimeException} on purpose: business rejections
 * must NOT be retried and must NOT trip the circuit breaker (retrying a deterministic rejection
 * wastes time and produces the wrong saga compensation path). It is therefore listed under
 * {@code resilience4j.*.instances.inventory-service.ignore-exceptions} so Resilience4j neither
 * retries it nor counts it as a circuit-breaker failure, and it propagates straight through the
 * client wrapper to the saga step for correct handling.
 */
public class InventoryBusinessException extends RuntimeException {

    public InventoryBusinessException(String message, StatusRuntimeException cause) {
        super(message, cause);
    }
}
