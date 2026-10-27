package com.robomart.common.audit;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

public record AuditEvent(
        String actor,
        AuditAction action,
        String entityType,
        String entityId,
        Instant timestamp,
        @Nullable String traceId,
        @Nullable String correlationId) {}
