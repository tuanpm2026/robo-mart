package com.robomart.order.exception;

import com.robomart.common.logging.ErrorCodeValue;

public enum OrderErrorCode implements ErrorCodeValue {
    PRODUCT_NOT_FOUND,
    ORDER_NOT_CANCELLABLE,
    ORDER_PAYMENT_FAILED,
    ORDER_INVENTORY_FAILED
}
