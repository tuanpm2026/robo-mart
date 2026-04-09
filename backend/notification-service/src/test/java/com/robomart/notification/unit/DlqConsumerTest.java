package com.robomart.notification.unit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.notification.event.DlqConsumer;

@ExtendWith(MockitoExtension.class)
class DlqConsumerTest {

    private final DlqConsumer dlqConsumer = new DlqConsumer();

    @Test
    void shouldLogAllDltHeadersOnDlqMessage() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("kafka_dlt-original-topic", "order.order.status-changed".getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-original-partition", ByteBuffer.allocate(Integer.BYTES).putInt(0).array());
        headers.add("kafka_dlt-original-offset", ByteBuffer.allocate(Long.BYTES).putLong(42L).array());
        headers.add("kafka_dlt-exception-fqcn", "java.lang.RuntimeException".getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-exception-message", "simulated failure".getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_delivery_attempt", "4".getBytes(StandardCharsets.UTF_8));
        headers.add("x-dlq-first-failure-timestamp", "2026-04-09T10:00:00Z".getBytes(StandardCharsets.UTF_8));
        headers.add("x-dlq-last-failure-timestamp", "2026-04-09T10:00:03Z".getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-original-consumer-group", "notification-order-status-group".getBytes(StandardCharsets.UTF_8));

        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>("notification.dlq", 0, 0L, "order-123", "payload");
        headers.forEach(h -> record.headers().add(h));

        // Should not throw — logging-only consumer
        dlqConsumer.onDlqMessage(record);
    }

    @Test
    void shouldHandleMissingHeadersGracefully() {
        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>("notification.dlq", 0, 0L, null, null);

        // Should not throw NPE — all headers absent, fallback to "unknown"
        dlqConsumer.onDlqMessage(record);
    }
}
