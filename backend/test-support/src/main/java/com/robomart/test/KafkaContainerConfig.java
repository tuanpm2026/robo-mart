package com.robomart.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfig {

    private static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0")
                    .withReuse(true);

    static {
        KAFKA.start();
    }

    @Bean
    public ConfluentKafkaContainer kafkaContainer() {
        return KAFKA;
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties(ConfluentKafkaContainer kafka) {
        return registry -> {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
            registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://test-schema-registry");
        };
    }
}
