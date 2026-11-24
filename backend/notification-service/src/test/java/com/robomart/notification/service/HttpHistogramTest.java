package com.robomart.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class HttpHistogramTest {
    @Test
    void readsRealPrometheusHistogramAcrossRoutesInsteadOfNonexistentActuatorQuantileTag() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var fast = io.micrometer.core.instrument.Timer.builder("http.server.requests")
                    .tag("uri", "/fast").publishPercentileHistogram().register(registry);
            var slow = io.micrometer.core.instrument.Timer.builder("http.server.requests")
                    .tag("uri", "/slow").publishPercentileHistogram().register(registry);
            for (int i = 0; i < 90; i++) fast.record(Duration.ofMillis(1));
            for (int i = 0; i < 10; i++) slow.record(Duration.ofSeconds(1));
            assertThat(HealthAggregatorService.histogramP95(registry.scrape())).isBetween(.9, 1.1);
            assertThat(HealthAggregatorService.histogramP95("# no samples")).isNull();
        } finally { registry.close(); }
    }
}
