package com.robomart.cart.dto;

import com.robomart.cart.entity.Cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateCartItemRequest(
        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = Cart.MAX_ITEM_QUANTITY, message = "Quantity must not exceed " + Cart.MAX_ITEM_QUANTITY)
        Integer quantity
) {
}
