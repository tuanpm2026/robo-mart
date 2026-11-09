package com.robomart.saga;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Bounded metric labels; workflow/order identities stay in durable audit records. */
@Component
public class SagaMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong(),
            manual = new AtomicLong(),
            oldestSeconds = new AtomicLong();

    public SagaMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        meters.gauge("saga.workflows.pending", pending);
        meters.gauge("saga.workflows.manual", manual);
        meters.gauge("saga.queue.oldest.seconds", oldestSeconds);
    }

    @Scheduled(fixedDelayString = "${saga.metrics.interval-ms:30000}")
    public void refresh() {
        var row =
                jdbc.queryForMap(
                        "SELECT count(*) FILTER (WHERE due_at IS NOT NULL AND manual_review_at IS"
                            + " NULL) AS pending, count(*) FILTER (WHERE manual_review_at IS NOT"
                            + " NULL) AS manual, COALESCE(EXTRACT(epoch FROM now()-min(due_at)"
                            + " FILTER (WHERE due_at<now())),0)::bigint AS oldest FROM"
                            + " saga_instances");
        pending.set(((Number) row.get("pending")).longValue());
        manual.set(((Number) row.get("manual")).longValue());
        oldestSeconds.set(((Number) row.get("oldest")).longValue());
    }
}
