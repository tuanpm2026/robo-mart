package com.robomart.product.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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


import com.robomart.product.config.OutboxProperties;
import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.product.event.producer.ProductEventProducer;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/** Sends independently claimed aggregate heads without holding a database transaction. */
@Service
public class OutboxPollingService {
    private static final Logger log = LoggerFactory.getLogger(OutboxPollingService.class);
    private final ProductOutboxStore store;
    private final ProductEventProducer productEventProducer;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final MeterRegistry meters;
    private final Semaphore inFlight = new Semaphore(50);
    private final ExecutorService sends = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), this::captureContext);

    public OutboxPollingService(ProductOutboxStore store, ProductEventProducer productEventProducer,
            ObjectMapper objectMapper, OutboxProperties properties, MeterRegistry meters) {
        this.store = store;
        this.productEventProducer = productEventProducer;
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

    private CompletableFuture<?> startSend(ProductOutboxStore.Delivery delivery) {
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

    private record Pending(ProductOutboxStore.Delivery delivery, CompletableFuture<?> future) { }

    @SuppressWarnings("unchecked")
    private CompletableFuture<?> publishEvent(ProductOutboxStore.Delivery event) {
        Map<String, Object> payload = objectMapper.readValue(event.payload(), Map.class);
        String aggregateId = event.aggregateId();
        Instant now = event.createdAt();

        switch (event.eventType()) {
            case "PRODUCT_CREATED" -> {
                var avroEvent = ProductCreatedEvent.newBuilder()
                        .setEventId(event.eventId().toString())
                        .setEventType("PRODUCT_CREATED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("PRODUCT")
                        .setTimestamp(now)
                        .setVersion(1)
                        .setProductId(toLong(payload.get("id")))
                        .setSku((String) payload.get("sku"))
                        .setName((String) payload.get("name"))
                        .setDescription((String) payload.get("description"))
                        .setPrice(String.valueOf(payload.get("price")))
                        .setCategoryId(toLong(payload.get("categoryId")))
                        .setCategoryName((String) payload.get("categoryName"))
                        .setBrand((String) payload.get("brand"))
                        .setRating(payload.get("rating") != null ? String.valueOf(payload.get("rating")) : null)
                        .setStockQuantity(toInt(payload.get("stockQuantity")))
                        .build();
                return productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_CREATED, aggregateId, avroEvent);
            }
            case "PRODUCT_UPDATED" -> {
                var avroEvent = ProductUpdatedEvent.newBuilder()
                        .setEventId(event.eventId().toString())
                        .setEventType("PRODUCT_UPDATED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("PRODUCT")
                        .setTimestamp(now)
                        .setVersion(1)
                        .setProductId(toLong(payload.get("id")))
                        .setSku((String) payload.get("sku"))
                        .setName((String) payload.get("name"))
                        .setDescription((String) payload.get("description"))
                        .setPrice(String.valueOf(payload.get("price")))
                        .setCategoryId(toLong(payload.get("categoryId")))
                        .setCategoryName((String) payload.get("categoryName"))
                        .setBrand((String) payload.get("brand"))
                        .setRating(payload.get("rating") != null ? String.valueOf(payload.get("rating")) : null)
                        .setStockQuantity(toInt(payload.get("stockQuantity")))
                        .build();
                return productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_UPDATED, aggregateId, avroEvent);
            }
            case "PRODUCT_DELETED" -> {
                var avroEvent = ProductDeletedEvent.newBuilder()
                        .setEventId(event.eventId().toString())
                        .setEventType("PRODUCT_DELETED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("PRODUCT")
                        .setTimestamp(now)
                        .setVersion(1)
                        .setProductId(toLong(payload.get("id")))
                        .setSku((String) payload.get("sku"))
                        .build();
                return productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_DELETED, aggregateId, avroEvent);
            }
            default -> throw new IllegalArgumentException("Unknown event type: " + event.eventType());
        }
    }

    private long toLong(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot convert null to long");
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private int toInt(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot convert null to int");
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
