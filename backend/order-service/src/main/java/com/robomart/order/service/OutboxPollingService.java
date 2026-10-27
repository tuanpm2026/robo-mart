package com.robomart.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.events.order.OrderCancelledEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.order.config.OutboxProperties;
import com.robomart.order.event.producer.OrderEventProducer;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.ObjectMapper;

/** Sends independently claimed aggregate heads without holding a database transaction. */
@Service
public class OutboxPollingService {
    private static final Logger log = LoggerFactory.getLogger(OutboxPollingService.class);
    private static final int EVENT_VERSION = 1;
    private final OrderOutboxStore store;
    private final OrderEventProducer orderEventProducer;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final MeterRegistry meters;
    private final Semaphore inFlight = new Semaphore(50);
    private final ExecutorService sends = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), this::captureContext);

    public OutboxPollingService(OrderOutboxStore store, OrderEventProducer orderEventProducer,
            ObjectMapper objectMapper, OutboxProperties properties, MeterRegistry meters) {
        this.store = store;
        this.orderEventProducer = orderEventProducer;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}",
            initialDelayString = "${outbox.initial-delay-ms:1000}")
    public void pollAndPublish() {
        if (properties.getClaimDuration().compareTo(properties.getPublishTimeout()) <= 0) {
            throw new IllegalStateException("Outbox claim duration must exceed the publish timeout");
        }
        Instant now = Instant.now();
        List<Pending> pending = new ArrayList<>();
        for (var delivery : store.claim(now, now.plus(properties.getClaimDuration()))) {
            var future = CompletableFuture.supplyAsync(() -> startSend(delivery), sends)
                    .thenCompose(result -> result);
            pending.add(new Pending(delivery, future));
        }
        if (pending.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(pending.stream().map(Pending::future).toArray(CompletableFuture[]::new))
                    .handle((result, error) -> null).get(properties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            meters.counter("robomart.outbox.publish.timeout").increment();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Outbox completion handler failed", failure);
        }
        for (var send : pending) {
            if (!send.future().isDone()) {
                // Unknown delivery: retain the lease. A stable event ID makes a later replay deduplicable.
                continue;
            }
            if (send.future().isCompletedExceptionally()) {
                store.release(send.delivery());
            } else if (store.acknowledge(send.delivery())) {
                meters.counter("robomart.outbox.published").increment();
            }
        }
    }

    private CompletableFuture<?> startSend(OrderOutboxStore.Delivery delivery) {
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Outbox send limit reached"));
        }
        try {
            return publishEvent(delivery).whenComplete((result, error) -> {
                inFlight.release();
                if (error != null) {
                    log.error("Outbox delivery failed: id={}, type={}", delivery.id(), delivery.eventType(), error);
                    meters.counter("robomart.outbox.publish.failed").increment();
                }
            });
        } catch (RuntimeException failure) {
            inFlight.release();
            log.error("Outbox event cannot be published: id={}, type={}", delivery.id(), delivery.eventType(), failure);
            meters.counter("robomart.outbox.publish.failed").increment();
            if (failure instanceof IllegalArgumentException) {
                meters.counter("robomart.outbox.unmappable").increment();
            }
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Scheduled(fixedDelayString = "${outbox.retention-interval-ms:3600000}")
    @SchedulerLock(name = "OrderOutboxRetention", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")
    public void retainPublished() {
        int deleted = store.deletePublishedBefore(Instant.now().minus(properties.getRetention()));
        meters.counter("robomart.outbox.retention.deleted").increment(deleted);
    }

    private ContextSnapshot captureContext() {
        ContextRegistry registry = new ContextRegistry();
        ContextRegistry.getInstance().getThreadLocalAccessors().forEach(registry::registerThreadLocalAccessor);
        registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("correlationId", "traceId", "spanId"));
        return ContextSnapshotFactory.builder().contextRegistry(registry).build().captureAll();
    }

    @PreDestroy
    public void shutdown() {
        sends.shutdownNow();
    }

    private record Pending(OrderOutboxStore.Delivery delivery, CompletableFuture<?> future) { }

    @SuppressWarnings("unchecked")
    private CompletableFuture<?> publishEvent(OrderOutboxStore.Delivery event) {
        Map<String, Object> payload = objectMapper.readValue(event.payload(), Map.class);
        String aggregateId = event.aggregateId();
        // Deterministic eventId derived from the outbox row so a re-publish carries a stable id
        // (consumer-side dedup friendly).
        String eventId = UUID.nameUUIDFromBytes(
                ("order-outbox-" + event.id()).getBytes(StandardCharsets.UTF_8)).toString();
        Instant timestamp = event.createdAt();

        switch (event.eventType()) {
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
            default -> throw new IllegalArgumentException("Unknown order outbox event type: " + event.eventType());
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
