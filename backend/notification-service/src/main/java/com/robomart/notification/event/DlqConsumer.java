package com.robomart.notification.event;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.repository.FailedEventRepository;

@Component
public class DlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqConsumer.class);

    private final FailedEventRepository failedEventRepository;

    public DlqConsumer(FailedEventRepository failedEventRepository) {
        this.failedEventRepository = failedEventRepository;
    }

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

        try {
            String preview = "unknown";
            try {
                if (record.value() != null) {
                    preview = record.value().toString();
                    if (preview.length() > 500) {
                        preview = preview.substring(0, 500) + "...";
                    }
                }
            } catch (Exception e) {
                log.warn("Could not serialize DLQ payload: {}", e.getMessage());
            }

            int retryCountInt = 0;
            try {
                if (!"unknown".equals(retryCount)) {
                    retryCountInt = Integer.parseInt(retryCount);
                }
            } catch (NumberFormatException ignored) {
                // leave as 0
            }

            String errorMsg = "unknown".equals(exceptionMessage) ? null : exceptionMessage;
            if (errorMsg != null && errorMsg.length() > 2000) {
                errorMsg = errorMsg.substring(0, 2000);
            }

            FailedEvent failedEvent = new FailedEvent();
            failedEvent.setEventType(originalTopic);
            failedEvent.setAggregateId(aggregateId);
            failedEvent.setOriginalTopic(originalTopic);
            failedEvent.setErrorClass("unknown".equals(exceptionClass) ? null : exceptionClass);
            failedEvent.setErrorMessage(errorMsg);
            failedEvent.setPayloadPreview(preview);
            failedEvent.setRetryCount(retryCountInt);
            failedEvent.setStatus("PENDING");

            if (aggregateId != null
                    && failedEventRepository.existsByOriginalTopicAndAggregateIdAndStatus(
                            originalTopic, aggregateId, "PENDING")) {
                log.debug("DLQ event already tracked (topic={}, aggregateId={}), skipping duplicate",
                        originalTopic, aggregateId);
                return;
            }
            failedEventRepository.save(failedEvent);
        } catch (Exception e) {
            log.warn("Failed to persist DLQ event to database: {}", e.getMessage());
        }
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
