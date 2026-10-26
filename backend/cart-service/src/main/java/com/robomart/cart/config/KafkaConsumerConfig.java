package com.robomart.cart.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {
    @Bean
    public ProducerFactory<String, Object> cartDltProducerFactory(
            ProducerFactory<String, SpecificRecord> producerFactory) {
        Map<String, Object> props = new HashMap<>(producerFactory.getConfigurationProperties());
        props.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        var serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(), SpecificRecord.class, new KafkaAvroSerializer()), true);
        var factory = new DefaultKafkaProducerFactory<String, Object>(props, new StringSerializer(), serializer);
        factory.setPhysicalCloseTimeout(2);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> cartDltTemplate(
            @Qualifier("cartDltProducerFactory") ProducerFactory<String, Object> factory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrap,
            @Value("${spring.kafka.properties.schema.registry.url}") String schemaRegistry,
            @Qualifier("cartDltTemplate") KafkaTemplate<String, Object> dltTemplate) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class);
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistry);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);
        props.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
                "com.robomart.common.kafka.CorrelationIdKafkaConsumerInterceptor");
        var recoverer = new DeadLetterPublishingRecoverer(dltTemplate,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(40));
        var backoff = new ExponentialBackOffWithMaxRetries(2);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(2000);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backoff));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public KafkaAdmin cartKafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        KafkaAdmin admin = new KafkaAdmin(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        admin.setOperationTimeout(5);
        return admin;
    }

    @Bean(name = "kafka", destroyMethod = "close")
    public CartKafkaHealthIndicator kafkaHealth(KafkaAdmin admin) {
        return new CartKafkaHealthIndicator(Admin.create(admin.getConfigurationProperties()));
    }

    @Bean
    public KafkaAdmin.NewTopics cartLifecycleTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("product.product.updated.DLT").partitions(3).replicas(1).build());
    }
}
