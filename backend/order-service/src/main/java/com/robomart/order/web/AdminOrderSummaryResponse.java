package com.robomart.order.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.robomart.order.enums.OrderStatus;

public record AdminOrderSummaryResponse(
        Long id,
        String userId,
        Instant createdAt,
        BigDecimal totalAmount,
        OrderStatus status,
        int itemCount,
        String cancellationReason) {
}
