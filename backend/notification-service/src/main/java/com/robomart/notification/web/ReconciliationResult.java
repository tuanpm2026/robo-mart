package com.robomart.notification.web;

import java.time.Instant;
import java.util.List;

public record ReconciliationResult(
        String type,
        List<ReconciliationDiscrepancy> discrepancies,
        boolean hasDiscrepancies,
        Instant checkedAt) {}
