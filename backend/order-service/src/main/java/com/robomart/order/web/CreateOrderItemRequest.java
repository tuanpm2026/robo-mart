package com.robomart.order.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderItemRequest(
        @NotBlank String productId,
        @NotBlank String productName,
        @Positive int quantity,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice) {
}
