package com.robomart.order.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.robomart.order.enums.OrderStatus;

public record AdminOrderDetailResponse(
        Long id,
        String userId,
        Instant createdAt,
        Instant updatedAt,
        BigDecimal totalAmount,
        OrderStatus status,
        String shippingAddress,
        String cancellationReason,
        List<OrderItemResponse> items,
        List<OrderStatusHistoryResponse> statusHistory) {
}
