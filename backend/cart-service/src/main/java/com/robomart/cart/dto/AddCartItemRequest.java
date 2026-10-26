package com.robomart.cart.dto;

import java.math.BigDecimal;

import com.robomart.cart.entity.Cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Add-to-cart request.
 *
 * <p>{@code productName} and {@code price} are accepted for backward compatibility with existing
 * clients but are <strong>ignored</strong>: the cart always uses the name and price returned by
 * product-service.
 */
public record AddCartItemRequest(
        @NotNull(message = "Product ID is required")
        @Min(value = 1, message = "Product ID must be positive")
        Long productId,

        String productName,

        BigDecimal price,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = Cart.MAX_ITEM_QUANTITY, message = "Quantity must not exceed " + Cart.MAX_ITEM_QUANTITY)
        Integer quantity
) {

    public AddCartItemRequest(Long productId, Integer quantity) {
        this(productId, null, null, quantity);
    }
}
