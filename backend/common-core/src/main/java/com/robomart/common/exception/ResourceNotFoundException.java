package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCode;
import com.robomart.common.logging.ErrorCodeValue;

public class ResourceNotFoundException extends RoboMartException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resourceType, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND,
                String.format("%s not found with id: %s", resourceType, id));
    }

    protected ResourceNotFoundException(ErrorCodeValue errorCode, String message) {
        super(errorCode, message);
    }
}
