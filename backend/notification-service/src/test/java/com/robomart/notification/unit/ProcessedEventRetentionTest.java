package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.robomart.notification.service.ProcessedEventRetentionJob;

class ProcessedEventRetentionTest {
    @Test
    void retentionCannotUndercutKafkaDlqWindow() {
        assertThatThrownBy(() -> new ProcessedEventRetentionJob(mock(JdbcTemplate.class), new SimpleMeterRegistry(),
                Duration.ofDays(6))).isInstanceOf(IllegalArgumentException.class);
    }
}
