package com.robomart.payment.dto;

import java.time.Instant;
import java.util.List;

public record ReconciliationSummaryResponse(List<OrderPaymentSummary> payments, Instant generatedAt,
        int page, int size, boolean hasNext) {
    public ReconciliationSummaryResponse(List<OrderPaymentSummary> payments, Instant generatedAt) {
        this(payments, generatedAt, 0, payments.size(), false);
    }

    public record OrderPaymentSummary(String orderId, String status, String amount) {}
}
