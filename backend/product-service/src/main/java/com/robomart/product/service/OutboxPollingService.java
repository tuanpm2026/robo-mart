package com.robomart.product.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.outbox.OutboxDispatcher;
import com.robomart.product.config.OutboxProperties;
import com.robomart.product.event.producer.ProductEventProducer;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/** Owns event mapping and schedules; the shared engine owns delivery and fencing semantics. */
@Service
public class OutboxPollingService {
    private final ProductEventProducer productEventProducer;
    private final ObjectMapper objectMapper;
    private final OutboxDispatcher<ProductOutboxStore.Delivery> publisher;

    public OutboxPollingService(ProductOutboxStore store, ProductEventProducer productEventProducer,
            ObjectMapper objectMapper, OutboxProperties properties, MeterRegistry meters) {
        this.productEventProducer = productEventProducer;
        this.objectMapper = objectMapper;
        this.publisher = new OutboxDispatcher<>(store, this::publishEvent, properties, meters);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}",
            initialDelayString = "${outbox.initial-delay-ms:1000}")
    public void pollAndPublish() {
        publisher.poll();
    }

    @PreDestroy
    public void shutdown() {
        publisher.close();
    }

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
