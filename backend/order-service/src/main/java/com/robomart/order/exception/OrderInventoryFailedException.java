package com.robomart.order.exception;

import com.robomart.common.exception.BusinessRuleException;

public class OrderInventoryFailedException extends BusinessRuleException {
    public OrderInventoryFailedException(String message) {
        super(OrderErrorCode.ORDER_INVENTORY_FAILED, message);
    }
}
