package com.robomart.notification.config;

import java.util.Map;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

import io.confluent.kafka.serializers.KafkaAvroSerializer;

/** Keep Boot's connection, security, observation and interceptor configuration. */
@Configuration(proxyBeanMethods = false)
public class KafkaProducerConfig {
    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaProducerFactoryCustomizer avroAndRawRecordSerializers() {
        return factory -> {
            // Boot's customizer erases its Object/Object factory's type parameters.
            var producer = (DefaultKafkaProducerFactory<Object, Object>) factory;
            producer.setKeySerializerSupplier(() -> new DelegatingByTypeSerializer(Map.of(
                    String.class, new StringSerializer(), byte[].class, new ByteArraySerializer())));
            producer.setValueSerializerSupplier(() -> new DelegatingByTypeSerializer(Map.of(
                    SpecificRecord.class, new KafkaAvroSerializer(), byte[].class, new ByteArraySerializer()), true));
            producer.setPhysicalCloseTimeout(2);
        };
    }
}
