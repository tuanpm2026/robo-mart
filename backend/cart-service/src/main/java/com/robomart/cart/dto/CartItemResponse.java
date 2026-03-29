package com.robomart.cart.dto;

import java.math.BigDecimal;

public record CartItemResponse(
        Long productId,
        String productName,
        BigDecimal price,
        int quantity,
        BigDecimal subtotal
) {
}
