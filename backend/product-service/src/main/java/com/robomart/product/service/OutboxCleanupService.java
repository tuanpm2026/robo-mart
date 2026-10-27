package com.robomart.product.service;


import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.product.config.OutboxProperties;

/** Delete a bounded batch of delivered records under one database-time scheduler lock. */
@Service
public class OutboxCleanupService {
    private final ProductOutboxStore store;
    private final OutboxProperties properties;
    private final MeterRegistry meters;

    public OutboxCleanupService(ProductOutboxStore store, OutboxProperties properties, MeterRegistry meters) {
        this.store = store;
        this.properties = properties;
        this.meters = meters;
    }

    @Scheduled(cron = "${outbox.retention-cron:0 0 2 * * *}")
    @SchedulerLock(name = "ProductOutboxRetention", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")
    public void cleanupOldEvents() {
        com.robomart.outbox.OutboxRetention.cleanup(store, properties, meters);
    }
}
