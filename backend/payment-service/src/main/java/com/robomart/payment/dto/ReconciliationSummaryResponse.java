package com.robomart.payment.dto;

import java.time.Instant;
import java.util.List;

public record ReconciliationSummaryResponse(List<OrderPaymentSummary> payments, Instant generatedAt) {
    public record OrderPaymentSummary(String orderId, String status, String amount) {}
}
