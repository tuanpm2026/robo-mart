package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCode;

public class ExternalServiceException extends RoboMartException {

    public ExternalServiceException(String message) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
