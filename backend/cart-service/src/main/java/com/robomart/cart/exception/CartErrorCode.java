package com.robomart.cart.exception;

import com.robomart.common.logging.ErrorCodeValue;

public enum CartErrorCode implements ErrorCodeValue {
    PRODUCT_NOT_FOUND,
    CART_NOT_FOUND,
    CART_ITEM_NOT_FOUND,
    CART_EXPIRED
}
