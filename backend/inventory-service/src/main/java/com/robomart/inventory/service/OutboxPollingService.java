package com.robomart.inventory.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.events.inventory.StockReleasedEvent;
import com.robomart.events.inventory.StockReservedEvent;
import com.robomart.inventory.config.OutboxProperties;
import com.robomart.inventory.event.producer.InventoryEventProducer;
import com.robomart.outbox.OutboxDispatcher;
import com.robomart.outbox.OutboxRetention;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.ObjectMapper;

/** Owns event mapping and schedules; the shared engine owns delivery and fencing semantics. */
@Service
public class OutboxPollingService {
    private static final int EVENT_VERSION = 1;
    private final InventoryEventProducer inventoryEventProducer;
    private final ObjectMapper objectMapper;
    private final OutboxDispatcher<InventoryOutboxStore.Delivery> publisher;
    private final InventoryOutboxStore store;
    private final OutboxProperties properties;
    private final MeterRegistry meters;

    public OutboxPollingService(InventoryOutboxStore store, InventoryEventProducer inventoryEventProducer,
            ObjectMapper objectMapper, OutboxProperties properties, MeterRegistry meters) {
        this.inventoryEventProducer = inventoryEventProducer;
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
    @SchedulerLock(name = "InventoryOutboxRetention", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")
    public void retainPublished() {
        OutboxRetention.cleanup(store, properties, meters);
    }

    @PreDestroy
    public void shutdown() {
        publisher.close();
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<?> publishEvent(InventoryOutboxStore.Delivery event) {
        Map<String, Object> payload = objectMapper.readValue(event.payload(), Map.class);
        String aggregateId = event.aggregateId();
        Instant now = event.createdAt();

        switch (event.eventType()) {
            case "stock_low_alert" -> {
                var avroEvent = StockLowAlertEvent.newBuilder()
                        .setEventId(event.eventId().toString())
                        .setEventType("STOCK_LOW_ALERT")
                        .setAggregateId(aggregateId)
                        .setAggregateType("InventoryItem")
                        .setTimestamp(now)
                        .setVersion(EVENT_VERSION)
                        .setProductId(String.valueOf(payload.get("productId")))
                        .setCurrentQuantity(toInt(payload.get("availableQuantity")))
                        .setThreshold(toInt(payload.get("lowStockThreshold")))
                        .build();
                return inventoryEventProducer.send(InventoryEventProducer.TOPIC_STOCK_LOW_ALERT,
                        aggregateId, avroEvent);
            }
            case "stock_reserved" -> {
                var avroEvent = StockReservedEvent.newBuilder()
                        .setEventId(event.eventId().toString())
                        .setEventType("STOCK_RESERVED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("InventoryItem")
                        .setTimestamp(now)
                        .setVersion(EVENT_VERSION)
                        .setOrderId(String.valueOf(payload.get("orderId")))
                        .setProductId(String.valueOf(payload.get("productId")))
                        .setQuantity(toInt(payload.get("quantity")))
                        .build();
                return inventoryEventProducer.send(InventoryEventProducer.TOPIC_STOCK_RESERVED,
                        aggregateId, avroEvent);
            }
            case "stock_released" -> {
                var avroEvent = StockReleasedEvent.newBuilder()
                        .setEventId(event.eventId().toString())
                        .setEventType("STOCK_RELEASED")
                        .setAggregateId(aggregateId)
                        .setAggregateType("InventoryItem")
                        .setTimestamp(now)
                        .setVersion(EVENT_VERSION)
                        .setOrderId(String.valueOf(payload.get("orderId")))
                        .setProductId(String.valueOf(payload.get("productId")))
                        .setQuantity(toInt(payload.get("quantity")))
                        .setReason("Stock released from order " + payload.get("orderId"))
                        .build();
                return inventoryEventProducer.send(InventoryEventProducer.TOPIC_STOCK_RELEASED,
                        aggregateId, avroEvent);
            }
            default -> throw new IllegalArgumentException("Unknown inventory outbox event type: " + event.eventType());
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
