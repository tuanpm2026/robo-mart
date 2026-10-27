package com.robomart.notification.web;

import java.time.Instant;
import java.util.List;

public record ReconciliationResult(String type, List<ReconciliationDiscrepancy> discrepancies,
        boolean hasDiscrepancies, Instant checkedAt, String status, String error) {
    public ReconciliationResult(String type, List<ReconciliationDiscrepancy> discrepancies,
            boolean hasDiscrepancies, Instant checkedAt) {
        this(type, discrepancies, hasDiscrepancies, checkedAt, "OK", null);
    }
}
