package com.robomart.inventory.event.producer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Tracer;

@Component
public class InventoryEventProducer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventProducer.class);

    public static final String TOPIC_STOCK_LOW_ALERT = "inventory.stock.low-alert";
    public static final String TOPIC_STOCK_RESERVED = "inventory.stock.reserved";
    public static final String TOPIC_STOCK_RELEASED = "inventory.stock.released";

    private final KafkaTemplate<String, SpecificRecord> kafkaTemplate;
    private final Tracer tracer;

    public InventoryEventProducer(KafkaTemplate<String, SpecificRecord> kafkaTemplate, Tracer tracer) {
        this.kafkaTemplate = kafkaTemplate;
        this.tracer = tracer;
    }

    public CompletableFuture<SendResult<String, SpecificRecord>> send(String topic, String key,
                                                                       SpecificRecord event) {
        var record = new ProducerRecord<String, SpecificRecord>(topic, key, event);

        var span = tracer.currentSpan();
        if (span != null && span.context() != null) {
            record.headers().add("x-trace-id",
                    span.context().traceId().getBytes(StandardCharsets.UTF_8));
        }

        String correlationId = org.slf4j.MDC.get("correlationId");
        if (correlationId != null) {
            record.headers().add("x-correlation-id",
                    correlationId.getBytes(StandardCharsets.UTF_8));
        }

        log.debug("Publishing event to topic={}, key={}", topic, key);

        return kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event to topic={}, key={}", topic, key, ex);
            } else {
                log.debug("Event published to topic={}, partition={}, offset={}",
                        topic, result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
