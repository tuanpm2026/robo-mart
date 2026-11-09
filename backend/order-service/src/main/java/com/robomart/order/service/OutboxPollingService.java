package com.robomart.order.service;

import com.robomart.events.order.OrderCancelledEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.order.config.OutboxProperties;
import com.robomart.order.event.producer.OrderEventProducer;
import com.robomart.outbox.OutboxDispatcher;
import com.robomart.outbox.OutboxRetention;
import com.robomart.outbox.StableEventIds;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Owns event mapping and schedules; the shared engine owns delivery and fencing semantics. */
@Service
public class OutboxPollingService {
    private static final int EVENT_VERSION = 1;
    private final OrderEventProducer orderEventProducer;
    private final ObjectMapper objectMapper;
    private final OutboxDispatcher<OrderOutboxStore.Delivery> publisher;
    private final OrderOutboxStore store;
    private final OutboxProperties properties;
    private final MeterRegistry meters;

    public OutboxPollingService(OrderOutboxStore store, OrderEventProducer orderEventProducer,
            ObjectMapper objectMapper, OutboxProperties properties, MeterRegistry meters) {
        this.orderEventProducer = orderEventProducer;
        this.objectMapper = objectMapper;
        this.store = store;
        this.properties = properties;
        this.meters = meters;
        this.publisher = new OutboxDispatcher<>(store, this::publishEvent, properties, meters);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}",
            initialDelayString = "${outbox.initial-delay-ms:1000}")
    public void pollAndPublish() {
        publisher.poll();
    }

    @Scheduled(fixedDelayString = "${outbox.retention-interval-ms:3600000}")
    @SchedulerLock(name = "OrderOutboxRetention", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")
    public void retainPublished() {
        OutboxRetention.cleanup(store, properties, meters);
    }

    @PreDestroy
    public void shutdown() {
        publisher.close();
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<?> publishEvent(OrderOutboxStore.Delivery event) {
        Map<String, Object> payload = objectMapper.readValue(event.payload(), Map.class);
        String aggregateId = event.aggregateId();
        // Deterministic eventId derived from the outbox row so a re-publish carries a stable id
        // (consumer-side dedup friendly).
        String eventId = StableEventIds.fromRow("order-outbox-", event.id());
        Instant timestamp = event.createdAt();

        switch (event.eventType()) {
            case "checkout_saga_requested" -> {
                var request =
                        com.robomart.events.order.CheckoutSagaRequestedEvent.newBuilder()
                                .setEventId(eventId)
                                .setRequestId(asString(payload.get("requestId")))
                                .setRequestType(asString(payload.get("requestType")))
                                .setRequestSequence(
                                        ((Number) payload.get("requestSequence")).longValue())
                                .setOrderId(aggregateId)
                                .setSagaId(asString(payload.get("sagaId")))
                                .setOwnerEpoch(((Number) payload.get("ownerEpoch")).longValue())
                                .setWorkflowVersion(1)
                                .setSnapshotJson(asString(payload.get("snapshotJson")))
                                .setReason(asString(payload.get("reason")))
                                .build();
                return orderEventProducer.send(
                        com.robomart.order.workflow.OrderWorkflowCoordinator.TOPIC,
                        aggregateId,
                        request);
            }
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
                return orderEventProducer.send(OrderEventProducer.TOPIC_STATUS_CHANGED, aggregateId, avroEvent);
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
                return orderEventProducer.send(OrderEventProducer.TOPIC_CANCELLED, aggregateId, avroEvent);
            }
            default ->
                    throw new IllegalArgumentException(
                            "Unknown order outbox event type: " + event.eventType());
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
