package com.robomart.order.saga.exception;

public class SagaStepException extends RuntimeException {

    private final String cancellationReason;
    private final boolean shouldCompensate;
    private final boolean shouldHoldAsPending;

    public SagaStepException(String message, boolean shouldCompensate) {
        super(message);
        this.cancellationReason = null;
        this.shouldCompensate = shouldCompensate;
        this.shouldHoldAsPending = false;
    }

    public SagaStepException(String message, Throwable cause, boolean shouldCompensate) {
        super(message, cause);
        this.cancellationReason = null;
        this.shouldCompensate = shouldCompensate;
        this.shouldHoldAsPending = false;
    }

    public SagaStepException(String message, Throwable cause, boolean shouldCompensate, boolean shouldHoldAsPending) {
        this(message, cause, shouldCompensate, shouldHoldAsPending, null);
    }

    public SagaStepException(String message, Throwable cause, boolean shouldCompensate,
            boolean shouldHoldAsPending, String cancellationReason) {
        super(message, cause);
        this.cancellationReason = cancellationReason;
        if (shouldCompensate && shouldHoldAsPending) {
            throw new IllegalArgumentException(
                    "shouldCompensate and shouldHoldAsPending cannot both be true — " +
                    "holding as pending means inventory stays reserved, compensation must not run");
        }
        this.shouldCompensate = shouldCompensate;
        this.shouldHoldAsPending = shouldHoldAsPending;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public boolean isShouldCompensate() {
        return shouldCompensate;
    }

    public boolean isShouldHoldAsPending() {
        return shouldHoldAsPending;
    }
}
