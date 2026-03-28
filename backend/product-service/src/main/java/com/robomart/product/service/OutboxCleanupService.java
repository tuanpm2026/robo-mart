package com.robomart.product.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.product.repository.OutboxEventRepository;

@Service
public class OutboxCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupService.class);
    private static final int RETENTION_DAYS = 7;

    private final OutboxEventRepository outboxEventRepository;

    public OutboxCleanupService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupOldEvents() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deleteByPublishedTrueAndPublishedAtBefore(cutoff);
        log.info("Outbox cleanup: deleted {} published events older than {} days", deleted, RETENTION_DAYS);
    }
}
