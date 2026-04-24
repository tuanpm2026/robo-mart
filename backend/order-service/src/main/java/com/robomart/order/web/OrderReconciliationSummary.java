package com.robomart.order.web;

import java.util.List;

public record OrderReconciliationSummary(String orderId, String status, List<OrderItemSummary> items) {
    public record OrderItemSummary(Long productId, int quantity) {}
}
