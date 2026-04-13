package com.robomart.notification.web;

import java.time.Instant;

public record ServiceHealthData(
        String service,
        String displayName,
        String actuatorStatus,
        Long p95ResponseTimeMs,
        Double cpuPercent,
        Double memoryPercent,
        Integer dbPoolActive,
        Integer dbPoolMax,
        Long kafkaConsumerLag,
        String consumerGroup,
        Instant checkedAt
) {}
