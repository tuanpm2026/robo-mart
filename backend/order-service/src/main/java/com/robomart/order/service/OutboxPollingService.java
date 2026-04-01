package com.robomart.order.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.repository.OutboxEventRepository;

@Service
public class OutboxPollingService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingService.class);
    private static final int BATCH_SIZE = 50;
    private static final String TOPIC_STATUS_CHANGED = "order.order.status-changed";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxPollingService(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            TransactionTemplate transactionTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void pollAndPublish() {
        List<OutboxEvent> unpublished = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        List<OutboxEvent> batch = unpublished.stream().limit(BATCH_SIZE).toList();

        if (batch.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox event(s)", batch.size());

        for (OutboxEvent event : batch) {
            try {
                String topic = resolveTopicForEvent(event.getEventType());
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get();
                transactionTemplate.execute(status -> {
                    event.markPublished();
                    outboxEventRepository.save(event);
                    return null;
                });
                log.debug("Published outbox event id={}, type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}: {}", event.getId(), e.getMessage(), e);
            }
        }
    }

    private String resolveTopicForEvent(String eventType) {
        return switch (eventType) {
            case "order_status_changed" -> TOPIC_STATUS_CHANGED;
            default -> "order." + eventType;
        };
    }
}
