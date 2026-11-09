package com.robomart.saga;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

class SagaMetricsTest {
    @Test
    void publishesBacklogManualReviewAndAgeWithoutIdentityLabels() {
        var jdbc = mock(JdbcTemplate.class);
        var meters = new SimpleMeterRegistry();
        var metrics = new SagaMetrics(jdbc, meters);
        when(jdbc.queryForMap(anyString()))
                .thenReturn(Map.of("pending", 3L, "manual", 1L, "oldest", 20L));
        metrics.refresh();
        assertThat(meters.get("saga.workflows.pending").gauge().value()).isEqualTo(3);
        assertThat(meters.get("saga.workflows.manual").gauge().value()).isEqualTo(1);
        assertThat(meters.get("saga.queue.oldest.seconds").gauge().value()).isEqualTo(20);
    }
}
