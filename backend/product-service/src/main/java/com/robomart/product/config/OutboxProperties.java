package com.robomart.product.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {
    private Duration publishTimeout = Duration.ofSeconds(10);
    private Duration claimDuration = Duration.ofMinutes(2);
    private Duration retention = Duration.ofDays(7);

    public Duration getPublishTimeout() {
        return publishTimeout;
    }

    public void setPublishTimeout(Duration value) {
        requirePositive(value);
        publishTimeout = value;
    }

    public Duration getClaimDuration() {
        return claimDuration;
    }

    public void setClaimDuration(Duration value) {
        requirePositive(value);
        claimDuration = value;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration value) {
        requirePositive(value);
        retention = value;
    }

    private static void requirePositive(Duration value) {
        if (value == null || value.toMillis() < 1) {
            throw new IllegalArgumentException("Outbox durations must be at least one millisecond");
        }
    }
}
