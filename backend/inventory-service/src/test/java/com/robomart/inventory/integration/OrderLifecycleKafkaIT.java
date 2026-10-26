package com.robomart.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.inventory.service.OrderLifecycleService;
import com.robomart.test.KafkaContainerConfig;
import com.robomart.test.PostgresContainerConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "robomart.inventory.consumer-enabled=true", "robomart.inventory.consumer-group=inventory-lifecycle-it"})
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, KafkaContainerConfig.class})
class OrderLifecycleKafkaIT {
    private static final String TOPIC = "order.order.status-changed";
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;
    @Autowired private KafkaTemplate<String, SpecificRecord> kafka;
    @MockitoSpyBean private OrderLifecycleService lifecycle;
    @Autowired private com.robomart.inventory.service.InventoryService inventory;
    @Autowired private com.robomart.inventory.repository.InventoryItemRepository items;
    @Autowired private com.robomart.inventory.service.OutboxPollingService poller;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private KafkaConsumer<String, byte[]> dltConsumer() {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "inventory-dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        consumer.subscribe(java.util.List.of(TOPIC + ".DLT"));
        return consumer;
    }

    private ConsumerRecord<String, byte[]> awaitRecord(KafkaConsumer<String, byte[]> consumer, String key) {
        AtomicReference<ConsumerRecord<String, byte[]>> found = new AtomicReference<>();
        await().atMost(40, TimeUnit.SECONDS).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(300)).forEach(record -> {
                if (key.equals(record.key())) {
                    found.set(record);
                }
            });
            assertThat(found.get()).isNotNull();
        });
        return found.get();
    }

    @Test
    void failedBusinessProcessingRetriesThenPublishesAvroToDlt() throws Exception {
        String key = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("simulated storage failure"))
                .when(lifecycle).handle(eq(eventId), eq(key), eq("CONFIRMED"));
        var event = OrderStatusChangedEvent.newBuilder().setEventId(eventId).setEventType("ORDER_STATUS_CHANGED")
                .setAggregateId(key).setAggregateType("Order").setTimestamp(Instant.now()).setVersion(1)
                .setOrderId(key).setPreviousStatus("PAYMENT_PROCESSING").setNewStatus("CONFIRMED").build();
        try (var consumer = dltConsumer()) {
            kafka.send(TOPIC, key, event).get(10, TimeUnit.SECONDS);
            var record = awaitRecord(consumer, key);
            assertThat(new String(record.headers().lastHeader("kafka_dlt-original-topic").value(), StandardCharsets.UTF_8))
                    .isEqualTo(TOPIC);
            assertThat(record.value()).isNotEmpty();
            verify(lifecycle, times(3)).handle(eventId, key, "CONFIRMED");
        }
    }

    @Test
    void malformedAvroRetainsOriginalBytesInDlt() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] corrupt = "not-an-avro-envelope".getBytes(StandardCharsets.UTF_8);
        try (var consumer = dltConsumer();
                var producer = new KafkaProducer<String, byte[]>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(TOPIC, key, corrupt)).get(10, TimeUnit.SECONDS);
            assertThat(awaitRecord(consumer, key).value()).isEqualTo(corrupt);
        }
    }
    @Test
    void realOutboxPublishesThePersistedEventIdentityToKafka() throws Exception {
        long product = 12345678L;
        var item = new com.robomart.inventory.entity.InventoryItem();
        item.setProductId(product);
        item.setAvailableQuantity(10);
        item.setReservedQuantity(0);
        item.setTotalQuantity(10);
        item.setLowStockThreshold(1);
        items.saveAndFlush(item);
        String order = UUID.randomUUID().toString();
        inventory.reserveOrder(order, Map.of(product, 2));
        String eventId = jdbc.queryForObject("SELECT event_id::text FROM outbox_events "
                + "WHERE event_type='stock_reserved' AND payload->>'orderId'=?", String.class, order);
        String topic = "inventory.stock.reserved";
        try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "inventory-stock-test-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
                var deserializer = new io.confluent.kafka.serializers.KafkaAvroDeserializer()) {
            consumer.subscribe(java.util.List.of(topic));
            deserializer.configure(Map.of("schema.registry.url", "mock://test-schema-registry", "specific.avro.reader", true), false);
            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
                poller.pollAndPublish();
                assertThat(jdbc.queryForObject("SELECT published FROM outbox_events WHERE event_id::text=?",
                        Boolean.class, eventId)).isTrue();
            });
            var received = awaitRecord(consumer, String.valueOf(product));
            var event = (com.robomart.events.inventory.StockReservedEvent) deserializer.deserialize(topic, received.value());
            assertThat(event.getEventId()).isEqualTo(eventId);
            assertThat(event.getOrderId()).isEqualTo(order);
            assertThat(event.getQuantity()).isEqualTo(2);
        }
    }

}
