package com.robomart.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.events.order.OrderCancelledEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.event.producer.OrderEventProducer;
import com.robomart.order.repository.OutboxEventRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Polls the order outbox and publishes each row as an Avro event. The outbox stores the event
 * payload as JSON; this service converts it into the matching Avro {@link SpecificRecord} and
 * publishes through {@link OrderEventProducer} (Avro serializer + Schema Registry) so that the
 * notification-service consumer can deserialize it. Previously the payload was sent as a raw
 * JSON string via a String serializer, which the Avro consumer could not read — silently
 * breaking the entire order event flow.
 */
@Service
public class OutboxPollingService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingService.class);
    private static final int BATCH_SIZE = 50;
    private static final int EVENT_VERSION = 1;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final OrderEventProducer orderEventProducer;
    private final ObjectMapper objectMapper;

    public OutboxPollingService(
            OutboxEventRepository outboxEventRepository,
            OrderEventProducer orderEventProducer,
            ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.orderEventProducer = orderEventProducer;
        this.objectMapper = objectMapper;
    }

    // @Transactional so the FOR UPDATE SKIP LOCKED row locks are held until each event is marked
    // published and committed — otherwise two instances both publish the same rows (duplicate
    // Kafka events).
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> batch = outboxEventRepository.findUnpublishedSkipLocked(BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox event(s)", batch.size());

        for (OutboxEvent event : batch) {
            try {
                publishEvent(event);
                event.markPublished();
                outboxEventRepository.save(event);
                log.debug("Published outbox event id={}, type={}", event.getId(), event.getEventType());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while publishing outbox event id={}", event.getId());
                break;
            } catch (IllegalArgumentException e) {
                log.error("Corrupted/unmappable outbox event id={}, type={}: permanently skipping. Error: {}",
                        event.getId(), event.getEventType(), e.getMessage());
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}: {}", event.getId(), e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void publishEvent(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
        String aggregateId = event.getAggregateId();
        // Deterministic eventId derived from the outbox row so a re-publish carries a stable id
        // (consumer-side dedup friendly).
        String eventId = UUID.nameUUIDFromBytes(
                ("order-outbox-" + event.getId()).getBytes(StandardCharsets.UTF_8)).toString();
        Instant timestamp = event.getCreatedAt();

        switch (event.getEventType()) {
            case "order_status_changed" -> {
                var avroEvent = OrderStatusChangedEvent.newBuilder()
                        .setEventId(eventId)
                        .setEventType("ORDER_STATUS_CHANGED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("Order")
                        .setTimestamp(timestamp)
                        .setVersion(EVENT_VERSION)
                        .setOrderId(asString(payload.get("orderId")))
                        .setPreviousStatus(asString(payload.get("previousStatus")))
                        .setNewStatus(asString(payload.get("newStatus")))
                        .build();
                orderEventProducer.send(OrderEventProducer.TOPIC_STATUS_CHANGED, aggregateId, avroEvent)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            case "order_cancelled" -> {
                var avroEvent = OrderCancelledEvent.newBuilder()
                        .setEventId(eventId)
                        .setEventType("ORDER_CANCELLED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("Order")
                        .setTimestamp(timestamp)
                        .setVersion(EVENT_VERSION)
                        .setOrderId(asString(payload.get("orderId")))
                        .setReason(asString(payload.get("reason")))
                        .setCancelledBy(asString(payload.get("cancelledBy")))
                        .build();
                orderEventProducer.send(OrderEventProducer.TOPIC_CANCELLED, aggregateId, avroEvent)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            default -> throw new IllegalArgumentException("Unknown order outbox event type: " + event.getEventType());
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
