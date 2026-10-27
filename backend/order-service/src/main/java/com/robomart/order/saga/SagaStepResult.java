package com.robomart.order.saga;

/** Immutable RPC output, applied to the order only by the calling orchestrator. */
public record SagaStepResult(String reservationId, String paymentId) {
    public static final SagaStepResult NONE = new SagaStepResult(null, null);
}
