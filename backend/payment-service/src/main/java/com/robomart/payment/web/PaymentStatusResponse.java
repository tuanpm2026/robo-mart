package com.robomart.payment.web;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentStatusResponse(
        Long paymentId,
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        String transactionId,
        Instant createdAt
) {}
