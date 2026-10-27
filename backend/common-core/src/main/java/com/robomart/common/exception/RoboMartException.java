package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCodeValue;

import org.jspecify.annotations.Nullable;

public abstract class RoboMartException extends RuntimeException {

    private final ErrorCodeValue errorCode;

    protected RoboMartException(ErrorCodeValue errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected RoboMartException(ErrorCodeValue errorCode, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCodeValue getErrorCode() {
        return errorCode;
    }
}
