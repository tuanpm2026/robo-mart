package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCode;

public abstract class RoboMartException extends RuntimeException {

    private final ErrorCode errorCode;

    protected RoboMartException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected RoboMartException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
