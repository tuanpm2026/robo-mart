package com.robomart.product.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.product.service.ProductProjectionStore;
import com.robomart.product.service.ProcessedEventService;
import com.robomart.test.IntegrationTest;

@IntegrationTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductDltIT {
    private static final String TOPIC = "product.product.created";
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;
    @Value("${spring.kafka.properties.schema.registry.url}") private String schemaRegistry;
    @Autowired private KafkaTemplate<String, SpecificRecord> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private ProcessedEventService processed;
    @MockitoSpyBean private ProductProjectionStore source;

    @BeforeAll
    void start() { listeners.getListenerContainers().forEach(container -> container.start()); }
    @AfterAll
    void stop() { listeners.getListenerContainers().forEach(container -> container.stop()); }

    private KafkaConsumer<String, byte[]> dltConsumer() {
        var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "product-dlt-probe-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        consumer.subscribe(List.of(TOPIC + ".DLT"));
        return consumer;
    }

    private ConsumerRecord<String, byte[]> record(KafkaConsumer<String, byte[]> consumer, String key) {
        var found = new AtomicReference<ConsumerRecord<String, byte[]>>();
        await().atMost(40, TimeUnit.SECONDS).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(250)).forEach(value -> { if (key.equals(value.key())) found.set(value); });
            assertThat(found.get()).isNotNull();
        });
        return found.get();
    }

    @Test
    void failedProjectionRetriesThenPublishesAvroAndKeepsItsDedupMarkerUnset() throws Exception {
        long missing = Long.MAX_VALUE - 123;
        String key = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        var event = ProductCreatedEvent.newBuilder().setEventId(eventId).setEventType("PRODUCT_CREATED")
                .setAggregateId(key).setAggregateType("PRODUCT").setTimestamp(Instant.now()).setVersion(1)
                .setProductId(missing).setSku(key).setName("Missing source").setPrice("19.95")
                .setCategoryId(1L).setCategoryName("Test").setStockQuantity(1).build();
        try (var consumer = dltConsumer(); var avro = new io.confluent.kafka.serializers.KafkaAvroDeserializer()) {
            avro.configure(Map.of("schema.registry.url", schemaRegistry, "specific.avro.reader", true), false);
            kafka.send(TOPIC, key, event).get(10, TimeUnit.SECONDS);
            var failed = record(consumer, key);
            assertThat(new String(failed.headers().lastHeader("kafka_dlt-original-topic").value(), StandardCharsets.UTF_8))
                    .isEqualTo(TOPIC);
            var decoded = (ProductCreatedEvent) avro.deserialize(TOPIC + ".DLT", failed.value());
            assertThat(decoded.getEventId()).isEqualTo(eventId);
            assertThat(decoded.getProductId()).isEqualTo(missing);
            verify(source, times(3)).load(missing);
            assertThat(processed.isProcessed("product-service-product-index-group", eventId)).isFalse();
        }
    }

    @Test
    void malformedAvroRetainsItsOriginalBytesInDlt() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = "invalid-avro-envelope".getBytes(StandardCharsets.UTF_8);
        try (var consumer = dltConsumer(); var producer = new KafkaProducer<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(TOPIC, key, bytes)).get(10, TimeUnit.SECONDS);
            assertThat(record(consumer, key).value()).isEqualTo(bytes);
        }
    }
}
