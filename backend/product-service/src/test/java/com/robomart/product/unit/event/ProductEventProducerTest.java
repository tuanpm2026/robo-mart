package com.robomart.product.unit.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.product.event.producer.ProductEventProducer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
class ProductEventProducerTest {

    @Mock
    private KafkaTemplate<String, SpecificRecord> kafkaTemplate;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    @InjectMocks
    private ProductEventProducer productEventProducer;

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendEventToCorrectTopic() {
        var event = ProductCreatedEvent.newBuilder()
                .setEventId("evt-1")
                .setEventType("PRODUCT_CREATED")
                .setAggregateId("1")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(1L)
                .setSku("ELEC-001")
                .setName("Robot Toy")
                .setPrice("29.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();

        var sendResult = new SendResult<String, SpecificRecord>(null,
                new RecordMetadata(new TopicPartition("product.product.created", 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-123");

        productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_CREATED, "1", event);

        ArgumentCaptor<ProducerRecord<String, SpecificRecord>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, SpecificRecord> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("product.product.created");
        assertThat(record.key()).isEqualTo("1");
        assertThat(record.value()).isEqualTo(event);
        assertThat(record.headers().lastHeader("x-trace-id")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendWithoutTraceHeaderWhenNoActiveSpan() {
        var event = ProductCreatedEvent.newBuilder()
                .setEventId("evt-2")
                .setEventType("PRODUCT_CREATED")
                .setAggregateId("2")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(2L)
                .setSku("ELEC-002")
                .setName("Smart Watch")
                .setPrice("199.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(50)
                .build();

        var sendResult = new SendResult<String, SpecificRecord>(null,
                new RecordMetadata(new TopicPartition("product.product.created", 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(tracer.currentSpan()).thenReturn(null);

        productEventProducer.send(ProductEventProducer.TOPIC_PRODUCT_CREATED, "2", event);

        ArgumentCaptor<ProducerRecord<String, SpecificRecord>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        assertThat(captor.getValue().headers().lastHeader("x-trace-id")).isNull();
    }
}
