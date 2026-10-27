package com.robomart.order.exception;

import com.robomart.common.exception.BusinessRuleException;

public class OrderNotCancellableException extends BusinessRuleException {

    public OrderNotCancellableException(String message) {
        super(OrderErrorCode.ORDER_NOT_CANCELLABLE, message);
    }
}
