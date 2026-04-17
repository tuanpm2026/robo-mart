package com.robomart.common.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

public class CorrelationIdKafkaConsumerInterceptor<K, V> implements ConsumerInterceptor<K, V> {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        for (ConsumerRecord<K, V> record : records) {
            Header header = record.headers().lastHeader(CORRELATION_ID_HEADER);
            if (header != null) {
                String correlationId = new String(header.value(), StandardCharsets.UTF_8);
                if (!correlationId.isBlank()) {
                    MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
                } else {
                    MDC.remove(CORRELATION_ID_MDC_KEY);
                }
            } else {
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
        }
        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) { }

    @Override
    public void close() {
        MDC.remove(CORRELATION_ID_MDC_KEY);
    }

    @Override
    public void configure(Map<String, ?> configs) { }
}
