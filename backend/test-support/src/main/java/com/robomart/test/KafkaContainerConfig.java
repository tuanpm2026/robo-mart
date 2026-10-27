package com.robomart.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfig {

    private static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:8.2.4");

    static {
        KAFKA.start();
        KafkaTestFeatures.enableConsumerProtocol(KAFKA.getBootstrapServers());
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties() {
        return registry -> {
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
            registry.add("spring.kafka.consumer.properties.group.protocol", () -> "consumer");
            registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://test-schema-registry");
        };
    }
}
