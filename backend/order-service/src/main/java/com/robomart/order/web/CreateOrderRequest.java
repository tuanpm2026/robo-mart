package com.robomart.order.web;

import java.util.List;

public record CreateOrderRequest(List<CreateOrderItemRequest> items, String shippingAddress) {
}
