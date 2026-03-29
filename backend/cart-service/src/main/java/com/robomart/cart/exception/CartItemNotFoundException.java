package com.robomart.cart.exception;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.common.logging.ErrorCode;

public class CartItemNotFoundException extends ResourceNotFoundException {

    public CartItemNotFoundException(String cartId, Long productId) {
        super(ErrorCode.CART_ITEM_NOT_FOUND,
                String.format("Item with productId %d not found in cart '%s'", productId, cartId));
    }
}
