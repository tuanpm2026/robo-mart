package com.robomart.inventory.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.events.inventory.StockReleasedEvent;
import com.robomart.events.inventory.StockReservedEvent;
import com.robomart.inventory.entity.OutboxEvent;
import com.robomart.inventory.event.producer.InventoryEventProducer;
import com.robomart.inventory.repository.OutboxEventRepository;

import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxPollingService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingService.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;
    private final InventoryEventProducer inventoryEventProducer;
    private final ObjectMapper objectMapper;

    public OutboxPollingService(OutboxEventRepository outboxEventRepository,
                                InventoryEventProducer inventoryEventProducer,
                                ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.inventoryEventProducer = inventoryEventProducer;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1000)
    public void pollAndPublish() {
        var events = outboxEventRepository.findUnpublishedSkipLocked(BATCH_SIZE);
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
            } catch (IllegalArgumentException e) {
                log.error("Corrupted outbox event id={}, type={}: permanently skipping. Error: {}",
                        event.getId(), event.getEventType(), e.getMessage());
                event.markPublished();
                outboxEventRepository.save(event);
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
            case "stock_low_alert" -> {
                var avroEvent = StockLowAlertEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setEventType("STOCK_LOW_ALERT")
                        .setAggregateId(aggregateId)
                        .setAggregateType("InventoryItem")
                        .setTimestamp(now)
                        .setVersion(1)
                        .setProductId(String.valueOf(payload.get("productId")))
                        .setCurrentQuantity(toInt(payload.get("availableQuantity")))
                        .setThreshold(toInt(payload.get("lowStockThreshold")))
                        .build();
                inventoryEventProducer.send(InventoryEventProducer.TOPIC_STOCK_LOW_ALERT,
                        aggregateId, avroEvent).get(10, TimeUnit.SECONDS);
            }
            case "stock_reserved" -> {
                var avroEvent = StockReservedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setEventType("STOCK_RESERVED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("InventoryItem")
                        .setTimestamp(now)
                        .setVersion(1)
                        .setOrderId(String.valueOf(payload.get("orderId")))
                        .setProductId(String.valueOf(payload.get("productId")))
                        .setQuantity(toInt(payload.get("quantity")))
                        .build();
                inventoryEventProducer.send(InventoryEventProducer.TOPIC_STOCK_RESERVED,
                        aggregateId, avroEvent).get(10, TimeUnit.SECONDS);
            }
            case "stock_released" -> {
                var avroEvent = StockReleasedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setEventType("STOCK_RELEASED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("InventoryItem")
                        .setTimestamp(now)
                        .setVersion(1)
                        .setOrderId(String.valueOf(payload.get("orderId")))
                        .setProductId(String.valueOf(payload.get("productId")))
                        .setQuantity(toInt(payload.get("quantity")))
                        .setReason("Stock released from order " + payload.get("orderId"))
                        .build();
                inventoryEventProducer.send(InventoryEventProducer.TOPIC_STOCK_RELEASED,
                        aggregateId, avroEvent).get(10, TimeUnit.SECONDS);
            }
            default -> log.warn("Unknown inventory outbox event type: {}", event.getEventType());
        }
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
