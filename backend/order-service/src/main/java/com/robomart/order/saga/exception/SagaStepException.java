package com.robomart.order.saga.exception;

public class SagaStepException extends RuntimeException {

    private final boolean shouldCompensate;
    private final boolean shouldHoldAsPending;

    public SagaStepException(String message, boolean shouldCompensate) {
        super(message);
        this.shouldCompensate = shouldCompensate;
        this.shouldHoldAsPending = false;
    }

    public SagaStepException(String message, Throwable cause, boolean shouldCompensate) {
        super(message, cause);
        this.shouldCompensate = shouldCompensate;
        this.shouldHoldAsPending = false;
    }

    public SagaStepException(String message, Throwable cause, boolean shouldCompensate, boolean shouldHoldAsPending) {
        super(message, cause);
        if (shouldCompensate && shouldHoldAsPending) {
            throw new IllegalArgumentException(
                    "shouldCompensate and shouldHoldAsPending cannot both be true — " +
                    "holding as pending means inventory stays reserved, compensation must not run");
        }
        this.shouldCompensate = shouldCompensate;
        this.shouldHoldAsPending = shouldHoldAsPending;
    }

    public boolean isShouldCompensate() {
        return shouldCompensate;
    }

    public boolean isShouldHoldAsPending() {
        return shouldHoldAsPending;
    }
}
