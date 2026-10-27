package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCode;

import org.jspecify.annotations.Nullable;

public class ExternalServiceException extends RoboMartException {

    public ExternalServiceException(String message) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    public ExternalServiceException(String message, @Nullable Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
