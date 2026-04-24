package com.robomart.payment.dto;

import java.time.Instant;

public record AuditLogDto(
        Long id,
        String actor,
        String action,
        String entityType,
        String entityId,
        String traceId,
        String correlationId,
        Instant createdAt) {}
