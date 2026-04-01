package com.robomart.common.exception;

import com.robomart.common.logging.ErrorCode;

public class BusinessRuleException extends RoboMartException {

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    protected BusinessRuleException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
