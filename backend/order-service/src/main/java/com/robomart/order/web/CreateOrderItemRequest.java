package com.robomart.order.web;

import java.math.BigDecimal;

public record CreateOrderItemRequest(String productId, String productName, int quantity, BigDecimal unitPrice) {
}
