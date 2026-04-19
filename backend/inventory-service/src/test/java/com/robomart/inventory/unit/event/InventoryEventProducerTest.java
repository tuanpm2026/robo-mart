package com.robomart.inventory.unit.event;

import java.util.concurrent.CompletableFuture;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.robomart.inventory.event.producer.InventoryEventProducer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryEventProducerTest {

    @Mock
    private KafkaTemplate<String, SpecificRecord> kafkaTemplate;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    @Mock
    private SpecificRecord event;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, SpecificRecord>> recordCaptor;

    private InventoryEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new InventoryEventProducer(kafkaTemplate, tracer);
    }

    @Test
    void shouldSendEventToKafkaWhenCalled() {
        CompletableFuture<SendResult<String, SpecificRecord>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
        when(tracer.currentSpan()).thenReturn(null);

        producer.send(InventoryEventProducer.TOPIC_STOCK_RESERVED, "product-42", event);

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, SpecificRecord> captured = recordCaptor.getValue();
        assertThat(captured.topic()).isEqualTo(InventoryEventProducer.TOPIC_STOCK_RESERVED);
        assertThat(captured.key()).isEqualTo("product-42");
        assertThat(captured.value()).isEqualTo(event);
    }

    @Test
    void shouldAddTraceIdHeaderWhenSpanIsPresent() {
        CompletableFuture<SendResult<String, SpecificRecord>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-abc");

        producer.send(InventoryEventProducer.TOPIC_STOCK_RELEASED, "product-1", event);

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, SpecificRecord> captured = recordCaptor.getValue();
        assertThat(captured.headers().lastHeader("x-trace-id")).isNotNull();
    }

    @Test
    void shouldReturnFutureWhenEventIsSent() {
        CompletableFuture<SendResult<String, SpecificRecord>> expected = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(expected);
        when(tracer.currentSpan()).thenReturn(null);

        var result = producer.send(InventoryEventProducer.TOPIC_STOCK_LOW_ALERT, "product-5", event);

        assertThat(result).isNotNull();
    }

    @Test
    void shouldAddCorrelationIdHeaderWhenPresentInMdc() {
        org.slf4j.MDC.put("correlationId", "corr-123");
        try {
            CompletableFuture<SendResult<String, SpecificRecord>> future = new CompletableFuture<>();
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
            when(tracer.currentSpan()).thenReturn(null);

            producer.send(InventoryEventProducer.TOPIC_STOCK_RESERVED, "product-42", event);

            verify(kafkaTemplate).send(recordCaptor.capture());
            ProducerRecord<String, SpecificRecord> captured = recordCaptor.getValue();
            assertThat(captured.headers().lastHeader("x-correlation-id")).isNotNull();
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCompleteExceptionallyWhenKafkaSendFails() {
        CompletableFuture<SendResult<String, SpecificRecord>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
        when(tracer.currentSpan()).thenReturn(null);

        var result = producer.send(InventoryEventProducer.TOPIC_STOCK_RESERVED, "product-42", event);
        future.completeExceptionally(new RuntimeException("Kafka unavailable"));

        assertThat(result).isCompletedExceptionally();
    }
}
