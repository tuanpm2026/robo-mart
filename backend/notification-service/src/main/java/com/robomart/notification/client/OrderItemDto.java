package com.robomart.notification.client;

import java.math.BigDecimal;

public record OrderItemDto(
    String productName,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal subtotal
) {}
