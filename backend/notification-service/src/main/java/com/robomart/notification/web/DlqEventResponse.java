package com.robomart.notification.web;

import java.time.Instant;

public record DlqEventResponse(
        Long id,
        String eventType,
        String aggregateId,
        String originalTopic,
        String errorClass,
        String errorMessage,
        String payloadPreview,
        int retryCount,
        String status,
        Instant firstFailedAt,
        Instant lastAttemptedAt
) {}
