package com.robomart.order.saga.exception;

public class SagaStepException extends RuntimeException {

    private final boolean shouldCompensate;

    public SagaStepException(String message, boolean shouldCompensate) {
        super(message);
        this.shouldCompensate = shouldCompensate;
    }

    public SagaStepException(String message, Throwable cause, boolean shouldCompensate) {
        super(message, cause);
        this.shouldCompensate = shouldCompensate;
    }

    public boolean isShouldCompensate() {
        return shouldCompensate;
    }
}
