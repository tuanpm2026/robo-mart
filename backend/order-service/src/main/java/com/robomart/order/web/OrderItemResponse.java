package com.robomart.order.web;

import java.math.BigDecimal;

public record OrderItemResponse(Long productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {
}
