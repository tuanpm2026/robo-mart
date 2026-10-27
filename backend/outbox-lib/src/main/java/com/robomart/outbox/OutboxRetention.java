package com.robomart.outbox;

import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;

/** A bounded published-only delete; the caller owns its scheduler/distributed lock. */
public final class OutboxRetention {
    private OutboxRetention() { }

    public static void cleanup(OutboxStore<?> store, OutboxSettings settings, MeterRegistry meters) {
        int deleted = store.deletePublishedBefore(Instant.now().minus(settings.getRetention()));
        meters.counter("robomart.outbox.retention.deleted").increment(deleted);
    }
}
