package com.robomart.order.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.robomart.order.enums.OrderStatus;

public record OrderSummaryResponse(Long id, Instant createdAt, BigDecimal totalAmount, OrderStatus status, int itemCount, String cancellationReason) {
}
