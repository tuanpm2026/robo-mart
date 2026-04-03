package com.robomart.order.exception;

import com.robomart.common.exception.BusinessRuleException;
import com.robomart.common.logging.ErrorCode;

public class OrderInventoryFailedException extends BusinessRuleException {
    public OrderInventoryFailedException(String message) {
        super(ErrorCode.ORDER_INVENTORY_FAILED, message);
    }
}
