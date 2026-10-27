package com.robomart.cart.exception;

import com.robomart.common.exception.ResourceNotFoundException;

public class CartNotFoundException extends ResourceNotFoundException {

    public CartNotFoundException(String cartId) {
        super(CartErrorCode.CART_NOT_FOUND,
                String.format("Cart with id '%s' not found", cartId));
    }
}
