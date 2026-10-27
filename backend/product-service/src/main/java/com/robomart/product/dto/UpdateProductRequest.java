package com.robomart.product.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

public record UpdateProductRequest(
        @NotBlank String name,
        String description,
        @NotNull Long categoryId,
        @NotNull @DecimalMin("0.01") BigDecimal price,
        String brand,
        @Min(0) Integer version
) {
    public UpdateProductRequest(String name, String description, Long categoryId, BigDecimal price, String brand) {
        this(name, description, categoryId, price, brand, null);
    }
}
