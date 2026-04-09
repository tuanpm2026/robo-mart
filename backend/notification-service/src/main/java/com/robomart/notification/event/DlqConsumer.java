package com.robomart.notification.event;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqConsumer.class);

    @KafkaListener(
            topics = "notification.dlq",
            groupId = "notification-dlq-monitor-group",
            containerFactory = "dlqListenerContainerFactory")
    public void onDlqMessage(ConsumerRecord<String, Object> record) {
        Headers headers = record.headers();

        String originalTopic = extractHeader(headers, "kafka_dlt-original-topic");
        String partition = extractIntHeader(headers, "kafka_dlt-original-partition");
        String offset = extractLongHeader(headers, "kafka_dlt-original-offset");
        String exceptionClass = extractHeader(headers, "kafka_dlt-exception-fqcn");
        String exceptionMessage = extractHeader(headers, "kafka_dlt-exception-message");
        String retryCount = extractHeader(headers, "kafka_delivery_attempt");
        String firstFailureTs = extractHeader(headers, "x-dlq-first-failure-timestamp");
        String lastFailureTs = extractHeader(headers, "x-dlq-last-failure-timestamp");
        String consumerGroup = extractHeader(headers, "kafka_dlt-original-consumer-group");
        String aggregateId = record.key();

        log.error("DLQ message received: originalTopic={}, partition={}, offset={}, "
                + "exceptionClass={}, exceptionMessage={}, retryCount={}, "
                + "firstFailureTs={}, lastFailureTs={}, consumerGroup={}, aggregateId={}",
                originalTopic, partition, offset,
                exceptionClass, exceptionMessage, retryCount,
                firstFailureTs, lastFailureTs, consumerGroup, aggregateId);
    }

    private String extractHeader(Headers headers, String key) {
        var header = headers.lastHeader(key);
        if (header == null || header.value() == null) {
            return "unknown";
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private String extractIntHeader(Headers headers, String key) {
        var header = headers.lastHeader(key);
        if (header == null || header.value() == null) {
            return "unknown";
        }
        try {
            return String.valueOf(ByteBuffer.wrap(header.value()).getInt());
        } catch (Exception e) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
    }

    private String extractLongHeader(Headers headers, String key) {
        var header = headers.lastHeader(key);
        if (header == null || header.value() == null) {
            return "unknown";
        }
        try {
            return String.valueOf(ByteBuffer.wrap(header.value()).getLong());
        } catch (Exception e) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
    }
}
