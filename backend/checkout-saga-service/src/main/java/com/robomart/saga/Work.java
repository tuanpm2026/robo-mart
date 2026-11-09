package com.robomart.saga;

import com.robomart.common.saga.CheckoutSnapshot;

import java.time.Instant;
import java.util.UUID;

public record Work(
        UUID id,
        CheckoutSnapshot snapshot,
        String state,
        boolean cancelRequested,
        String reason,
        String reservationId,
        String paymentId,
        String chargeResolution,
        int attempts,
        Instant phaseStartedAt,
        UUID token) {
    public String key(String operation) {
        return id + ":" + operation;
    }
}
