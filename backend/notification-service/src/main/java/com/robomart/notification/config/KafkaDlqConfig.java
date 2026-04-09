package com.robomart.notification.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;

@Configuration
public class KafkaDlqConfig {

    static final String DLQ_TOPIC = "notification.dlq";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    // Task 1: Declare notification.dlq topic with 7-day retention
    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationDlqTopic() {
        return TopicBuilder.name(DLQ_TOPIC)
                .partitions(1)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                .build();
    }

    // Task 2: DLQ producer factory using KafkaAvroSerializer
    @Bean
    public ProducerFactory<String, Object> dlqProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> dlqKafkaTemplate(
            @Qualifier("dlqProducerFactory") ProducerFactory<String, Object> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            @Qualifier("dlqKafkaTemplate") KafkaTemplate<String, Object> dlqKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dlqKafkaTemplate,
                (record, ex) -> new TopicPartition(DLQ_TOPIC, 0));

        recoverer.setHeadersFunction((record, ex) -> {
            org.apache.kafka.common.header.internals.RecordHeaders headers =
                    new org.apache.kafka.common.header.internals.RecordHeaders();
            String now = java.time.Instant.now().toString();
            headers.add("x-dlq-first-failure-timestamp", now.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            headers.add("x-dlq-last-failure-timestamp", now.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return headers;
        });

        return recoverer;
    }

    // Task 3: DLQ consumer factory with SPECIFIC_AVRO_READER_CONFIG = false (GenericRecord)
    @Bean
    public ConsumerFactory<String, Object> dlqConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> dlqListenerContainerFactory(
            @Qualifier("dlqConsumerFactory") ConsumerFactory<String, Object> dlqConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dlqConsumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0, 0)));
        return factory;
    }
}
