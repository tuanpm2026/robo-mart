package com.robomart.order.exception;

import com.robomart.common.exception.BusinessRuleException;
import com.robomart.common.logging.ErrorCode;

public class OrderPaymentFailedException extends BusinessRuleException {
    public OrderPaymentFailedException(String message) {
        super(ErrorCode.ORDER_PAYMENT_FAILED, message);
    }
}
