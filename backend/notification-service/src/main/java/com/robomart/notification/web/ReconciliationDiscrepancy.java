package com.robomart.notification.web;

public record ReconciliationDiscrepancy(
        String entityType,
        String entityId,
        String expected,
        String actual,
        String suggestedResolution) {}
