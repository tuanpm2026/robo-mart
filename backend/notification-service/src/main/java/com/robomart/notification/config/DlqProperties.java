package com.robomart.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "notification.dlq")
public class DlqProperties {
    private Duration replayTimeout = Duration.ofSeconds(10);

    public Duration getReplayTimeout() { return replayTimeout; }
    public void setReplayTimeout(Duration value) {
        if (value == null || value.toMillis() < 1 || value.compareTo(Duration.ofMinutes(2)) >= 0) {
            throw new IllegalArgumentException("Replay timeout must be positive and shorter than the two-minute claim");
        }
        replayTimeout = value;
    }
}
