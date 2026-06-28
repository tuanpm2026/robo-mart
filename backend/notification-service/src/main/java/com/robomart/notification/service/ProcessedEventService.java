package com.robomart.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.notification.entity.ProcessedEvent;
import com.robomart.notification.repository.ProcessedEventRepository;

/**
 * Consumer-side idempotency guard. Kafka delivers at-least-once, so a consumer can receive the same
 * event more than once; this service lets a consumer skip events it has already handled, keyed by
 * {@code (consumerGroup, eventId)}.
 *
 * <p>Usage pattern (check-before, mark-after-success): the consumer skips when {@link #isProcessed}
 * is true, runs its side effect, then calls {@link #markProcessed}. Marking only after success means
 * a handler failure leaves the event unmarked so Kafka redelivery reprocesses it; the small residual
 * window (side effect succeeds, mark fails) yields at-most a duplicate, which the consumers' own
 * effects tolerate. Not transactional with the side effect by design.
 */
@Service
public class ProcessedEventService {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventService.class);

    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional(readOnly = true)
    public boolean isProcessed(String consumerGroup, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            log.warn("Event with no eventId for consumerGroup={} — cannot dedup, processing anyway", consumerGroup);
            return false;
        }
        return processedEventRepository.existsByConsumerGroupAndEventId(consumerGroup, eventId);
    }

    @Transactional
    public void markProcessed(String consumerGroup, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(consumerGroup, eventId));
        } catch (DataIntegrityViolationException e) {
            // Concurrent delivery of the same event already recorded it — idempotent, ignore.
            log.debug("Event {} already marked processed for consumerGroup={}", eventId, consumerGroup);
        }
    }
}
