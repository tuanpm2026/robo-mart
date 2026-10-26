package com.robomart.order.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.order.service.OrderService;

/** Minimal internal read contract for safe reservation expiry; never routed by the gateway. */
@RestController
@PreAuthorize("hasRole('INVENTORY_SERVICE')")
public class InventoryOrderStatusController {
    private final OrderService orders;

    public InventoryOrderStatusController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/api/v1/internal/inventory/orders/{orderId:[0-9]+}/status")
    public ApiResponse<String> status(@PathVariable Long orderId) {
        return new ApiResponse<>(orders.getOrder(orderId).getStatus().name(), null);
    }
}
