package com.robomart.notification.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import com.robomart.notification.kafka.RawAvroDeserializer;
import com.robomart.notification.kafka.RawKeyDeserializer;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;

import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Value("${spring.kafka.listener.auto-startup:true}") private boolean autoStartup;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, RawKeyDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, RawAvroDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);
        props.put(SchemaRegistryClientConfig.HTTP_CONNECT_TIMEOUT_MS, 1000);
        props.put(SchemaRegistryClientConfig.HTTP_READ_TIMEOUT_MS, 5000);
        props.put(SchemaRegistryClientConfig.MAX_RETRIES_CONFIG, 0);
        props.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
                "com.robomart.common.kafka.CorrelationIdKafkaConsumerInterceptor");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            DeadLetterPublishingRecoverer dlqRecoverer) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setAutoStartup(autoStartup);
        factory.setConsumerFactory(consumerFactory());
        var retry = new ExponentialBackOffWithMaxRetries(2);
        retry.setInitialInterval(500);
        retry.setMultiplier(2);
        retry.setMaxInterval(2000);
        factory.setCommonErrorHandler(new DefaultErrorHandler(dlqRecoverer, retry));
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        factory.getContainerProperties().setShutdownTimeout(20_000L);
        return factory;
    }
}
