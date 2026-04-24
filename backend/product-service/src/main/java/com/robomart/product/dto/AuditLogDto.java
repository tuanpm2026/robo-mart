package com.robomart.product.dto;

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
