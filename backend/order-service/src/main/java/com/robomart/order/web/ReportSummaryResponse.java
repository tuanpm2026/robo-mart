package com.robomart.order.web;

import java.math.BigDecimal;
import java.util.List;

public record ReportSummaryResponse(
        List<TopProductEntry> topProducts,
        List<RevenueByProductEntry> revenueByProduct,
        List<OrderTrendEntry> orderTrends
) {

    public record TopProductEntry(
            Long productId,
            String productName,
            Long totalQuantity,
            BigDecimal totalRevenue
    ) {}

    public record RevenueByProductEntry(
            String productName,
            BigDecimal totalRevenue
    ) {}

    public record OrderTrendEntry(
            String date,
            String status,
            Long count
    ) {}
}
