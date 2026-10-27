package com.robomart.order.exception;

import com.robomart.common.exception.BusinessRuleException;

public class OrderPaymentFailedException extends BusinessRuleException {
    public OrderPaymentFailedException(String message) {
        super(OrderErrorCode.ORDER_PAYMENT_FAILED, message);
    }
}
