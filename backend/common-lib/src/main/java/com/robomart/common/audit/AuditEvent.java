package com.robomart.common.audit;

import java.time.Instant;

public record AuditEvent(
        String actor,
        AuditAction action,
        String entityType,
        String entityId,
        Instant timestamp,
        String traceId,
        String correlationId) {}
