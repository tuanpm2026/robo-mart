package com.robomart.inventory.unit.service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.inventory.entity.OutboxEvent;
import com.robomart.inventory.event.producer.InventoryEventProducer;
import com.robomart.inventory.repository.OutboxEventRepository;
import com.robomart.inventory.service.OutboxPollingService;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPollingServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private InventoryEventProducer inventoryEventProducer;

    private OutboxPollingService outboxPollingService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxPollingService = new OutboxPollingService(
                outboxEventRepository, inventoryEventProducer, objectMapper);
    }

    @Test
    void shouldDoNothingWhenNoUnpublishedEventsFound() {
        when(outboxEventRepository.findUnpublishedSkipLocked(50))
                .thenReturn(Collections.emptyList());

        outboxPollingService.pollAndPublish();

        verify(inventoryEventProducer, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldPublishStockReservedEventWhenEventTypeIsStockReserved() throws Exception {
        String payload = "{\"orderId\":\"order-1\",\"productId\":\"42\",\"quantity\":5}";
        OutboxEvent event = new OutboxEvent("InventoryItem", "42", "stock_reserved", payload);
        when(outboxEventRepository.findUnpublishedSkipLocked(50)).thenReturn(List.of(event));
        when(inventoryEventProducer.send(
                eq(InventoryEventProducer.TOPIC_STOCK_RESERVED), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPollingService.pollAndPublish();

        verify(inventoryEventProducer).send(
                eq(InventoryEventProducer.TOPIC_STOCK_RESERVED), anyString(), any());
        assertThat(event.isPublished()).isTrue();
        verify(outboxEventRepository).save(event);
    }

    @Test
    void shouldPublishStockReleasedEventWhenEventTypeIsStockReleased() throws Exception {
        String payload = "{\"orderId\":\"order-2\",\"productId\":\"10\",\"quantity\":3}";
        OutboxEvent event = new OutboxEvent("InventoryItem", "10", "stock_released", payload);
        when(outboxEventRepository.findUnpublishedSkipLocked(50)).thenReturn(List.of(event));
        when(inventoryEventProducer.send(
                eq(InventoryEventProducer.TOPIC_STOCK_RELEASED), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPollingService.pollAndPublish();

        verify(inventoryEventProducer).send(
                eq(InventoryEventProducer.TOPIC_STOCK_RELEASED), anyString(), any());
        assertThat(event.isPublished()).isTrue();
    }

    @Test
    void shouldPublishStockLowAlertEventWhenEventTypeIsStockLowAlert() throws Exception {
        String payload = "{\"productId\":\"7\",\"availableQuantity\":2,\"lowStockThreshold\":5}";
        OutboxEvent event = new OutboxEvent("InventoryItem", "7", "stock_low_alert", payload);
        when(outboxEventRepository.findUnpublishedSkipLocked(50)).thenReturn(List.of(event));
        when(inventoryEventProducer.send(
                eq(InventoryEventProducer.TOPIC_STOCK_LOW_ALERT), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPollingService.pollAndPublish();

        verify(inventoryEventProducer).send(
                eq(InventoryEventProducer.TOPIC_STOCK_LOW_ALERT), anyString(), any());
        assertThat(event.isPublished()).isTrue();
    }

    @Test
    void shouldMarkEventPublishedAndSkipWhenEventTypeIsUnknown() {
        String payload = "{}";
        OutboxEvent event = new OutboxEvent("InventoryItem", "5", "unknown_type", payload);
        when(outboxEventRepository.findUnpublishedSkipLocked(50)).thenReturn(List.of(event));

        outboxPollingService.pollAndPublish();

        verify(inventoryEventProducer, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldSkipAndLogWhenPayloadIsMalformed() {
        OutboxEvent event = new OutboxEvent("InventoryItem", "5", "stock_reserved", "invalid-json");
        when(outboxEventRepository.findUnpublishedSkipLocked(50)).thenReturn(List.of(event));

        outboxPollingService.pollAndPublish();

        verify(inventoryEventProducer, never()).send(anyString(), anyString(), any());
    }
}
