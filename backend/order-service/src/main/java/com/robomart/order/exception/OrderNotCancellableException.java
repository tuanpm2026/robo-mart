package com.robomart.order.exception;

import com.robomart.common.exception.BusinessRuleException;
import com.robomart.common.logging.ErrorCode;

public class OrderNotCancellableException extends BusinessRuleException {

    public OrderNotCancellableException(String message) {
        super(ErrorCode.ORDER_NOT_CANCELLABLE, message);
    }
}
