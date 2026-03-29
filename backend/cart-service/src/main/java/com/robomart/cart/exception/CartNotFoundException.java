package com.robomart.cart.exception;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.common.logging.ErrorCode;

public class CartNotFoundException extends ResourceNotFoundException {

    public CartNotFoundException(String cartId) {
        super(ErrorCode.CART_NOT_FOUND,
                String.format("Cart with id '%s' not found", cartId));
    }
}
