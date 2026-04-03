package com.robomart.order.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateOrderRequest(
        @NotEmpty @Valid List<CreateOrderItemRequest> items,
        @NotBlank String shippingAddress) {
}
