package com.robomart.product.exception;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.common.logging.ErrorCode;

public class ProductNotFoundException extends ResourceNotFoundException {

    public ProductNotFoundException(Long productId) {
        super(ErrorCode.PRODUCT_NOT_FOUND,
                String.format("Product with id %d not found", productId));
    }
}
