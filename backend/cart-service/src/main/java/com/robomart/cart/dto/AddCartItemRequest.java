package com.robomart.cart.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @NotNull(message = "Product ID is required")
        @Min(value = 1, message = "Product ID must be positive")
        Long productId,

        @NotBlank(message = "Product name is required")
        String productName,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0", message = "Price must be non-negative")
        BigDecimal price,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity
) {
}
