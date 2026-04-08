package com.robomart.order.web;

import com.robomart.order.enums.OrderStatus;

import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
}
