package com.robomart.order.web;

import java.time.Instant;

import com.robomart.order.enums.OrderStatus;

public record OrderStatusHistoryResponse(OrderStatus status, Instant changedAt) {
}
