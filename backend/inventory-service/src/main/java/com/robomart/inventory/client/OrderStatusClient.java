package com.robomart.inventory.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.robomart.common.dto.ApiResponse;

@HttpExchange(accept = "application/json")
public interface OrderStatusClient {
    @GetExchange("/api/v1/internal/inventory/orders/{orderId}/status")
    ApiResponse<String> status(@PathVariable("orderId") String orderId);
}
