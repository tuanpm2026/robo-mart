package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCode;

public class ValidationException extends RoboMartException {

    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }
}
