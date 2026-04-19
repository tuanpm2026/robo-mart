package com.robomart.test;

import java.util.Objects;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/**
 * Real Schema Registry container for integration/contract tests (Story 10.2).
 * Must be initialized with Kafka bootstrap servers after Kafka starts.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SchemaRegistryContainerConfig {

    private static final int SCHEMA_REGISTRY_PORT = 8081;

    private static GenericContainer<?> schemaRegistry;

    public static void initWithKafka(String kafkaBootstrapServers) {
        schemaRegistry = new GenericContainer<>("confluentinc/cp-schema-registry:7.8.0")
                .withExposedPorts(SCHEMA_REGISTRY_PORT)
                .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:" + SCHEMA_REGISTRY_PORT)
                .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", kafkaBootstrapServers);
        schemaRegistry.start();
    }

    @Bean
    DynamicPropertyRegistrar schemaRegistryProperties() {
        Objects.requireNonNull(schemaRegistry,
            "SchemaRegistryContainerConfig.initWithKafka() must be called before the Spring context loads. "
            + "Typically done in a @BeforeAll or static initializer that first starts the Kafka container.");
        return registry -> {
            registry.add("spring.kafka.properties.schema.registry.url",
                () -> "http://" + schemaRegistry.getHost()
                        + ":" + schemaRegistry.getMappedPort(SCHEMA_REGISTRY_PORT));
        };
    }
}
