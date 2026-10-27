package com.robomart.product.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
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

@EnableKafka
@Configuration
public class KafkaConsumerConfig {
    @Bean
    public ProducerFactory<String, Object> productDltProducerFactory(ProducerFactory<String, SpecificRecord> producerFactory) {
        Map<String, Object> props = new HashMap<>(producerFactory.getConfigurationProperties());
        props.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        var values = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(), SpecificRecord.class, new KafkaAvroSerializer()), true);
        var factory = new DefaultKafkaProducerFactory<String, Object>(props, new StringSerializer(), values);
        factory.setPhysicalCloseTimeout(2);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> productDltTemplate(
            @Qualifier("productDltProducerFactory") ProducerFactory<String, Object> factory) {
        var template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrap,
            @Value("${spring.kafka.properties.schema.registry.url}") String schemaRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class);
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistry);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("productDltTemplate") KafkaTemplate<String, Object> dlt,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        // Let the broker choose a DLT partition; source coordinates stay in the standard DLT headers.
        var recoverer = new DeadLetterPublishingRecoverer(dlt,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(40));
        var backoff = new ExponentialBackOffWithMaxRetries(2);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(2000);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backoff));
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setShutdownTimeout(20000);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.kafka.listener", name = "auto-startup", havingValue = "true", matchIfMissing = true)
    public KafkaAdmin.NewTopics productDeadLetterTopics(
            @Value("${product.kafka.dlt-replication-factor:1}") short replicationFactor) {
        if (replicationFactor < 1) throw new IllegalArgumentException("DLT replication factor must be positive");
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("product.product.created.DLT").partitions(3).replicas(replicationFactor).build(),
                TopicBuilder.name("product.product.updated.DLT").partitions(3).replicas(replicationFactor).build(),
                TopicBuilder.name("product.product.deleted.DLT").partitions(3).replicas(replicationFactor).build());
    }
}
