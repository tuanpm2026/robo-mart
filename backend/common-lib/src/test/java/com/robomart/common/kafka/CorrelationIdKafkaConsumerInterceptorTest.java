package com.robomart.common.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdKafkaConsumerInterceptorTest {

    private CorrelationIdKafkaConsumerInterceptor<String, String> interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CorrelationIdKafkaConsumerInterceptor<>();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void populatesMdcFromCorrelationIdHeader() {
        ConsumerRecord<String, String> record = recordWithHeader("test-correlation-abc");
        ConsumerRecords<String, String> records = singleRecord(record);

        interceptor.onConsume(records);

        assertThat(MDC.get("correlationId")).isEqualTo("test-correlation-abc");
    }

    @Test
    void clearsMdcWhenHeaderIsAbsent() {
        MDC.put("correlationId", "stale-id-from-previous-batch");
        ConsumerRecord<String, String> record = recordWithoutHeader();
        ConsumerRecords<String, String> records = singleRecord(record);

        interceptor.onConsume(records);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void clearsMdcWhenHeaderValueIsBlank() {
        MDC.put("correlationId", "stale-id");
        ConsumerRecord<String, String> record = recordWithHeader("  ");
        ConsumerRecords<String, String> records = singleRecord(record);

        interceptor.onConsume(records);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void clearsMdcOnClose() {
        MDC.put("correlationId", "some-id");

        interceptor.close();

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void lastRecordInBatchWins() {
        ConsumerRecord<String, String> first = recordWithHeader("first-id");
        ConsumerRecord<String, String> second = recordWithHeader("second-id");
        TopicPartition partition = new TopicPartition("topic", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(
                Map.of(partition, List.of(first, second)));

        interceptor.onConsume(records);

        assertThat(MDC.get("correlationId")).isEqualTo("second-id");
    }

    // -- helpers --

    private ConsumerRecord<String, String> recordWithHeader(String correlationId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0L, "key", "value");
        record.headers().add("X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private ConsumerRecord<String, String> recordWithoutHeader() {
        return new ConsumerRecord<>("topic", 0, 0L, "key", "value");
    }

    private ConsumerRecords<String, String> singleRecord(ConsumerRecord<String, String> record) {
        return new ConsumerRecords<>(Map.of(new TopicPartition("topic", 0), List.of(record)));
    }
}
