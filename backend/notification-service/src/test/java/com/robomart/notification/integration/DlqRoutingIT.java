package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.service.NotificationService;
import com.robomart.test.KafkaContainerConfig;
import com.robomart.test.PostgresContainerConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, KafkaContainerConfig.class, TestKafkaProducerConfig.class,
         DlqRoutingIT.DlqTestListener.class})
class DlqRoutingIT {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    private NotificationService notificationService;

    @Autowired
    private DlqTestListener dlqTestListener;

    @BeforeEach
    void setUp() {
        dlqTestListener.clear();
        doThrow(new RuntimeException("simulated failure"))
                .when(notificationService).sendOrderConfirmedNotifications(any());
    }

    @Test
    void shouldRoutePoisonEventToDlqAfterMaxRetries() throws InterruptedException {
        String orderId = "dlq-test-1";

        OrderStatusChangedEvent event = OrderStatusChangedEvent.newBuilder()
                .setEventId("evt-dlq-1")
                .setEventType("order_status_changed")
                .setAggregateId(orderId)
                .setAggregateType("Order")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setOrderId(orderId)
                .setPreviousStatus("PAYMENT_PROCESSING")
                .setNewStatus("CONFIRMED")
                .build();

        kafkaTemplate.send("order.order.status-changed", orderId, event);

        // Retries take 3 × 1s = ~3s minimum; give 60s headroom
        await().atMost(60, TimeUnit.SECONDS).until(() ->
                dlqTestListener.getQueue().peek() != null);

        ConsumerRecord<String, Object> dlqRecord = dlqTestListener.getQueue().poll(5, TimeUnit.SECONDS);
        assertThat(dlqRecord).isNotNull();

        String originalTopic = extractHeader(dlqRecord, "kafka_dlt-original-topic");
        assertThat(originalTopic).isEqualTo("order.order.status-changed");
    }

    private String extractHeader(ConsumerRecord<String, Object> record, String key) {
        var header = record.headers().lastHeader(key);
        if (header == null) {
            return "unknown";
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class DlqTestListener {

        private final BlockingQueue<ConsumerRecord<String, Object>> queue = new LinkedBlockingQueue<>();

        @KafkaListener(
                topics = "notification.dlq",
                groupId = "notification-dlq-test-group",
                containerFactory = "dlqListenerContainerFactory")
        public void onDlqMessage(ConsumerRecord<String, Object> record) {
            queue.offer(record);
        }

        public BlockingQueue<ConsumerRecord<String, Object>> getQueue() {
            return queue;
        }

        public void clear() {
            queue.clear();
        }
    }
}
