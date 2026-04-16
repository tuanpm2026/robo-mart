package com.robomart.order.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "saga")
public class SagaProperties {

    private Steps steps = new Steps();
    private DeadSagaDetection deadSagaDetection = new DeadSagaDetection();

    public Steps getSteps() {
        return steps;
    }

    public void setSteps(Steps steps) {
        this.steps = steps;
    }

    public DeadSagaDetection getDeadSagaDetection() {
        return deadSagaDetection;
    }

    public void setDeadSagaDetection(DeadSagaDetection deadSagaDetection) {
        this.deadSagaDetection = deadSagaDetection;
    }

    public static class Steps {
        private Duration defaultTimeout = Duration.ofSeconds(10);
        private Map<String, Duration> timeouts = new HashMap<>();

        public Duration getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }

        public Map<String, Duration> getTimeouts() {
            return timeouts;
        }

        public void setTimeouts(Map<String, Duration> timeouts) {
            this.timeouts = timeouts;
        }
    }

    public static class DeadSagaDetection {
        private boolean enabled = true;
        private Duration stuckThreshold = Duration.ofMinutes(5);
        private long checkIntervalMs = 60_000L;
        private long initialDelayMs = 30_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getStuckThreshold() {
            return stuckThreshold;
        }

        public void setStuckThreshold(Duration stuckThreshold) {
            this.stuckThreshold = stuckThreshold;
        }

        public long getCheckIntervalMs() {
            return checkIntervalMs;
        }

        public void setCheckIntervalMs(long checkIntervalMs) {
            this.checkIntervalMs = checkIntervalMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }
    }
}
