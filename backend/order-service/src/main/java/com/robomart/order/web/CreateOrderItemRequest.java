package com.robomart.order.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * One checkout line. {@code productName} and {@code unitPrice} are optional and ignored — they are
 * still accepted so existing clients keep working, but the order is priced from product-service.
 */
public record CreateOrderItemRequest(
        @NotBlank String productId,
        String productName,
        @Positive int quantity,
        BigDecimal unitPrice) {
}
