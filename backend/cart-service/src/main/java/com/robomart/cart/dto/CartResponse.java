package com.robomart.cart.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        String cartId,
        List<CartItemResponse> items,
        int totalItems,
        BigDecimal totalPrice
) {
}
