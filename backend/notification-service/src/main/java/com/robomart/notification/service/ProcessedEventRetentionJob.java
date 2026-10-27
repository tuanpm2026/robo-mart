package com.robomart.notification.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keep active failed-record dedup identities; bound each cleanup transaction. */
@Component
public class ProcessedEventRetentionJob {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final Duration retention;

    public ProcessedEventRetentionJob(JdbcTemplate jdbc, MeterRegistry meters,
            @Value("${notification.processed-retention.age:P30D}") Duration retention) {
        if (retention.compareTo(Duration.ofDays(7)) < 0) {
            throw new IllegalArgumentException("Processed-event retention must cover the seven-day Kafka DLQ window");
        }
        this.jdbc = jdbc;
        this.meters = meters;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${notification.processed-retention.interval:PT1H}")
    @SchedulerLock(name = "NotificationProcessedRetention", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        int deleted = jdbc.update("DELETE FROM processed_events WHERE id IN (SELECT p.id FROM processed_events p "
                + "WHERE p.processed_at<? AND NOT EXISTS (SELECT 1 FROM failed_events f "
                + "WHERE f.source_event_id=p.event_id AND f.source_consumer_group=p.consumer_group "
                + "AND f.status IN ('PENDING','REPLAYING')) ORDER BY p.processed_at,p.id LIMIT 10000)",
                Timestamp.from(Instant.now().minus(retention)));
        meters.counter("notification.processed.retention.deleted").increment(deleted);
    }
}
