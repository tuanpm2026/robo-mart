package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;

import com.robomart.product.entity.OutboxEvent;
import com.robomart.product.event.producer.ProductEventProducer;
import com.robomart.product.repository.OutboxEventRepository;
import com.robomart.product.service.OutboxPollingService;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxPollingServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ProductEventProducer productEventProducer;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxPollingService outboxPollingService;

    @Test
    void shouldDoNothingWhenNoUnpublishedEvents() {
        when(outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc())
                .thenReturn(Collections.emptyList());

        outboxPollingService.pollAndPublish();

        verify(productEventProducer, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldPublishEventAndMarkPublishedWhenSuccessful() throws Exception {
        String payload = "{\"id\":1,\"sku\":\"ELEC-001\",\"name\":\"Robot Toy\",\"description\":\"A fun toy\"," +
                "\"price\":29.99,\"categoryId\":1,\"categoryName\":\"Electronics\"," +
                "\"brand\":\"ToyBrand\",\"rating\":4.5,\"stockQuantity\":100}";

        OutboxEvent event = new OutboxEvent("PRODUCT", "1", "PRODUCT_CREATED", payload);
        when(outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(event));

        var sendResult = new SendResult<String, SpecificRecord>(null,
                new RecordMetadata(new TopicPartition("test", 0), 0, 0, 0, 0, 0));
        when(productEventProducer.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(objectMapper.readValue(eq(payload), eq(java.util.Map.class)))
                .thenReturn(java.util.Map.of(
                        "id", 1, "sku", "ELEC-001", "name", "Robot Toy",
                        "description", "A fun toy", "price", 29.99,
                        "categoryId", 1, "categoryName", "Electronics",
                        "brand", "ToyBrand", "rating", 4.5, "stockQuantity", 100
                ));

        outboxPollingService.pollAndPublish();

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isNotNull();

        ArgumentCaptor<SpecificRecord> recordCaptor = ArgumentCaptor.forClass(SpecificRecord.class);
        verify(productEventProducer).send(
                eq(ProductEventProducer.TOPIC_PRODUCT_CREATED),
                eq("1"),
                recordCaptor.capture()
        );
        verify(outboxEventRepository).save(event);
    }

    @Test
    void shouldNotMarkPublishedWhenSendFails() throws Exception {
        String payload = "{\"id\":1,\"sku\":\"ELEC-001\",\"name\":\"Robot Toy\"," +
                "\"price\":29.99,\"categoryId\":1,\"categoryName\":\"Electronics\",\"stockQuantity\":100}";

        OutboxEvent event = new OutboxEvent("PRODUCT", "1", "PRODUCT_CREATED", payload);
        when(outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(event));
        when(objectMapper.readValue(eq(payload), eq(java.util.Map.class)))
                .thenReturn(java.util.Map.of(
                        "id", 1, "sku", "ELEC-001", "name", "Robot Toy",
                        "price", 29.99, "categoryId", 1, "categoryName", "Electronics",
                        "stockQuantity", 100
                ));
        when(productEventProducer.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));

        outboxPollingService.pollAndPublish();

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPublishedAt()).isNull();
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }
}
