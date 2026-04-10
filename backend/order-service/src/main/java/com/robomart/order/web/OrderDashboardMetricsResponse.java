package com.robomart.order.web;

import java.math.BigDecimal;

public record OrderDashboardMetricsResponse(long ordersToday, BigDecimal revenueToday) {
}
