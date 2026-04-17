package com.robomart.common.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;

public class CorrelationIdKafkaProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            Headers headers = record.headers();
            if (headers.lastHeader(CORRELATION_ID_HEADER) == null) {
                headers.add(CORRELATION_ID_HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8));
            }
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) { }

    @Override
    public void close() { }

    @Override
    public void configure(Map<String, ?> configs) { }
}
