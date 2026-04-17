package com.robomart.common.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdKafkaProducerInterceptorTest {

    private CorrelationIdKafkaProducerInterceptor<String, String> interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CorrelationIdKafkaProducerInterceptor<>();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void addsCorrelationIdFromMdcAsKafkaHeader() {
        MDC.put("correlationId", "test-correlation-123");
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");

        ProducerRecord<String, String> result = interceptor.onSend(record);

        Header header = result.headers().lastHeader("X-Correlation-Id");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
            .isEqualTo("test-correlation-123");
    }

    @Test
    void doesNotAddHeaderWhenMdcIsEmpty() {
        MDC.clear();
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");

        ProducerRecord<String, String> result = interceptor.onSend(record);

        assertThat(result.headers().lastHeader("X-Correlation-Id")).isNull();
    }

    @Test
    void doesNotOverwriteExistingHeader() {
        MDC.put("correlationId", "new-id");
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");
        record.headers().add("X-Correlation-Id", "existing-id".getBytes(StandardCharsets.UTF_8));

        ProducerRecord<String, String> result = interceptor.onSend(record);

        assertThat(new String(
            result.headers().lastHeader("X-Correlation-Id").value(), StandardCharsets.UTF_8))
            .isEqualTo("existing-id");
    }

    @Test
    void doesNotAddHeaderWhenMdcValueIsBlank() {
        MDC.put("correlationId", "  ");
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");

        ProducerRecord<String, String> result = interceptor.onSend(record);

        assertThat(result.headers().lastHeader("X-Correlation-Id")).isNull();
    }
}
