package com.robomart.product.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

import com.robomart.product.entity.OutboxEvent;
import com.robomart.product.event.producer.ProductEventProducer;
import com.robomart.product.repository.OutboxEventRepository;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.events.product.ProductDeletedEvent;

import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxPollingService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ProductEventProducer productEventProducer;
    private final ObjectMapper objectMapper;

    public OutboxPollingService(OutboxEventRepository outboxEventRepository,
                                ProductEventProducer productEventProducer,
                                ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.productEventProducer = productEventProducer;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1000)
    public void pollAndPublish() {
        var events = outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc();
        if (events.isEmpty()) {
            return;
        }

        log.debug("Polling {} unpublished outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                publishEvent(event);
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while publishing outbox event id={}", event.getId());
                break;
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}, type={}: {}",
                        event.getId(), event.getEventType(), e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void publishEvent(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
        String aggregateId = event.getAggregateId();
        Instant now = Instant.now();

        switch (event.getEventType()) {
            case "PRODUCT_CREATED" -> {
                var avroEvent = ProductCreatedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
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
                productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_CREATED, aggregateId, avroEvent).get(10, TimeUnit.SECONDS);
            }
            case "PRODUCT_UPDATED" -> {
                var avroEvent = ProductUpdatedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
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
                productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_UPDATED, aggregateId, avroEvent).get(10, TimeUnit.SECONDS);
            }
            case "PRODUCT_DELETED" -> {
                var avroEvent = ProductDeletedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setEventType("PRODUCT_DELETED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("PRODUCT")
                        .setTimestamp(now)
                        .setVersion(1)
                        .setProductId(toLong(payload.get("id")))
                        .setSku((String) payload.get("sku"))
                        .build();
                productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_DELETED, aggregateId, avroEvent).get(10, TimeUnit.SECONDS);
            }
            default -> throw new IllegalStateException("Unknown event type: " + event.getEventType());
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
