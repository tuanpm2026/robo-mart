package com.robomart.order.exception;

import com.robomart.common.exception.BusinessRuleException;

/** A requested product does not exist, is deactivated, or has no sellable price. */
public class OrderProductUnavailableException extends BusinessRuleException {

    public OrderProductUnavailableException(String message) {
        super(OrderErrorCode.PRODUCT_NOT_FOUND, message);
    }
}
