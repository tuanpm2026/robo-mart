package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.repository.FailedEventRepository;
import com.robomart.notification.service.FailedEventService;
import com.robomart.notification.service.NotificationService;

import io.confluent.kafka.serializers.KafkaAvroSerializer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "notification.scheduling.enabled=false")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class DlqRoutingIT {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container private static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:8.2.4");
    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        com.robomart.test.KafkaTestFeatures.enableConsumerProtocol(KAFKA.getBootstrapServers());
        registry.add("spring.kafka.consumer.properties.group.protocol", () -> "consumer");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://notification-raw-replay-it");
    }

    @Autowired private KafkaTemplate<String, Object> kafka;
    @Autowired private KafkaTemplate<byte[], byte[]> rawKafka;
    @Autowired private FailedEventRepository events;
    @Autowired private FailedEventService replay;
    @Value("${spring.kafka.properties.schema.registry.url}") private String registry;
    @MockitoBean private NotificationService notifications;
    @MockitoSpyBean private com.robomart.notification.service.DlqRecordStore archive;

    private FailedEvent awaitEvent(String eventId) {
        await().atMost(40, TimeUnit.SECONDS).untilAsserted(() -> assertThat(events.findAll().stream()
                .anyMatch(event -> eventId.equals(event.getSourceEventId()))).isTrue());
        return events.findAll().stream().filter(event -> eventId.equals(event.getSourceEventId())).findFirst().orElseThrow();
    }

    @Test
    void avroFailureArchivesExactWireRecordAndReplayReachesOriginalConsumerOnce() throws Exception {
        String order = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("simulated business failure"))
                .when(notifications).sendOrderConfirmedNotifications(order);
        var event = OrderStatusChangedEvent.newBuilder().setEventId(eventId).setEventType("ORDER_STATUS_CHANGED")
                .setAggregateId(order).setAggregateType("Order").setTimestamp(Instant.now()).setVersion(1)
                .setOrderId(order).setPreviousStatus("PAYMENT_PROCESSING").setNewStatus("CONFIRMED").build();
        byte[] expected;
        try (var serializer = new KafkaAvroSerializer()) {
            serializer.configure(Map.of("schema.registry.url", registry), false);
            expected = serializer.serialize("order.order.status-changed", event);
        }
        var headers = new RecordHeaders().add("custom", new byte[]{1}).add("custom", new byte[]{2});
        var sent = kafka.send(new ProducerRecord<>("order.order.status-changed", null, null, order, event, headers))
                .get(10, TimeUnit.SECONDS);
        FailedEvent saved = awaitEvent(eventId);
        assertThat(saved.getRecordValue()).isEqualTo(expected);
        assertThat(saved.getRecordKey()).isEqualTo(order.getBytes(StandardCharsets.UTF_8));
        assertThat(saved.getOriginalOffset()).isEqualTo(sent.getRecordMetadata().offset());
        assertThat(saved.getOriginalPartition()).isEqualTo(sent.getRecordMetadata().partition());
        assertThat(saved.isRawRecordAvailable()).isTrue();
        verify(notifications, times(3)).sendOrderConfirmedNotifications(order);
        doNothing().when(notifications).sendOrderConfirmedNotifications(order);
        assertThat(replay.retryEvent(saved.getId())).isTrue();
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> verify(notifications, times(4))
                .sendOrderConfirmedNotifications(order));
        assertThat(events.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo("REPLAYED");
        assertThatThrownBy(() -> replay.retryEvent(saved.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalArchiveRetriesUnavailablePersistenceWithoutLosingTheRecord() throws Exception {
        String order = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("business failure awaiting archive"))
                .when(notifications).sendOrderConfirmedNotifications(order);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new org.springframework.dao.DataAccessResourceFailureException("archive unavailable");
            }
            return call.callRealMethod();
        }).when(archive).capture(org.mockito.ArgumentMatchers.any());
        var event = OrderStatusChangedEvent.newBuilder().setEventId(eventId).setEventType("ORDER_STATUS_CHANGED")
                .setAggregateId(order).setAggregateType("Order").setTimestamp(Instant.now()).setVersion(1)
                .setOrderId(order).setPreviousStatus("PAYMENT_PROCESSING").setNewStatus("CONFIRMED").build();
        kafka.send("order.order.status-changed", order, event).get(10, TimeUnit.SECONDS);

        FailedEvent saved = awaitEvent(eventId);
        assertThat(saved.isRawRecordAvailable()).isTrue();
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(events.findAll().stream().filter(e -> eventId.equals(e.getSourceEventId()))).hasSize(1);
    }

    @Test
    void malformedAvroAndNonUtf8KeyAreArchivedWithoutChangingBytes() throws Exception {
        byte[] key = {(byte) 255, (byte) 254, 0};
        byte[] value = {12, 34, (byte) 255, 0};
        var sent = rawKafka.send("order.order.status-changed", key, value).get(10, TimeUnit.SECONDS);
        await().atMost(40, TimeUnit.SECONDS).untilAsserted(() -> assertThat(events.findAll().stream()
                .anyMatch(e -> "order.order.status-changed".equals(e.getOriginalTopic())
                        && e.getOriginalOffset() == sent.getRecordMetadata().offset()
                        && e.getOriginalPartition() == sent.getRecordMetadata().partition())).isTrue());
        var saved = events.findAll().stream().filter(e -> e.getOriginalOffset() == sent.getRecordMetadata().offset()
                && e.getOriginalPartition() == sent.getRecordMetadata().partition()).findFirst().orElseThrow();
        assertThat(saved.getRecordKey()).isEqualTo(key);
        assertThat(saved.getRecordValue()).isEqualTo(value);
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.isRawRecordAvailable()).isTrue();
    }
}
