package com.robomart.product.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateProductRequest(
        @NotBlank String name,
        String description,
        @NotNull Long categoryId,
        @NotNull @DecimalMin("0.01") BigDecimal price,
        String brand
) {
}
