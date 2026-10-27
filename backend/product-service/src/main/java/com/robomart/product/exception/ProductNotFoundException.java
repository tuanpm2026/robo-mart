package com.robomart.product.exception;

import com.robomart.common.exception.ResourceNotFoundException;

public class ProductNotFoundException extends ResourceNotFoundException {

    public ProductNotFoundException(Long productId) {
        super(ProductErrorCode.PRODUCT_NOT_FOUND,
                String.format("Product with id %d not found", productId));
    }
}
