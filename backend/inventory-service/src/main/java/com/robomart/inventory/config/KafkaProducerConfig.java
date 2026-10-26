package com.robomart.inventory.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Bean
    public ProducerFactory<String, SpecificRecord> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                "com.robomart.common.kafka.CorrelationIdKafkaProducerInterceptor");
        props.put(SchemaRegistryClientConfig.HTTP_CONNECT_TIMEOUT_MS, 1000);
        props.put(SchemaRegistryClientConfig.HTTP_READ_TIMEOUT_MS, 5000);
        props.put(SchemaRegistryClientConfig.MAX_RETRIES_CONFIG, 0);
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, true);
        var factory = new DefaultKafkaProducerFactory<String, SpecificRecord>(props);
        factory.setPhysicalCloseTimeout(2);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, SpecificRecord> kafkaTemplate() {
        KafkaTemplate<String, SpecificRecord> template = new KafkaTemplate<>(producerFactory());
        template.setObservationEnabled(true);
        return template;
    }
}
