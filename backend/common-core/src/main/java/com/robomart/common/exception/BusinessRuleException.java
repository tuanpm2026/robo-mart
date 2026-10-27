package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCode;
import com.robomart.common.logging.ErrorCodeValue;

public class BusinessRuleException extends RoboMartException {

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    protected BusinessRuleException(ErrorCodeValue errorCode, String message) {
        super(errorCode, message);
    }
}
