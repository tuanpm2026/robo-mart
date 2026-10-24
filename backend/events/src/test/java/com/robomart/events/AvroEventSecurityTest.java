package com.robomart.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.apache.avro.util.ClassSecurityValidator;
import org.apache.avro.util.ClassUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.robomart.events.config.AvroEventSecurityAutoConfiguration;
import com.robomart.events.product.ProductCreatedEvent;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

class AvroEventSecurityTest {

    private final ClassSecurityValidator.ClassSecurityPredicate originalValidator = ClassSecurityValidator.getGlobal();

    @AfterEach
    void restoreValidator() {
        ClassSecurityValidator.setGlobal(originalValidator);
    }

    @Test
    void generatedEventRoundTripsWithSpecificAvroSerde() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AvroEventSecurityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductCreatedEvent event = ProductCreatedEvent.newBuilder()
                            .setEventId("event-1").setEventType("ProductCreated")
                            .setAggregateId("1").setAggregateType("Product")
                            .setTimestamp(Instant.ofEpochMilli(1000)).setVersion(1)
                            .setProductId(1L).setSku("SKU-1").setName("Robot")
                            .setPrice("10.00").setCategoryId(1L).setCategoryName("Robots")
                            .setStockQuantity(5).build();
                    Map<String, Object> properties = Map.of(
                            "schema.registry.url", "mock://event-security-test",
                            "specific.avro.reader", true);
                    try (KafkaAvroSerializer serializer = new KafkaAvroSerializer();
                            KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer()) {
                        serializer.configure(properties, false);
                        deserializer.configure(properties, false);
                        byte[] bytes = serializer.serialize("product-events", event);
                        assertThat(deserializer.deserialize("product-events", bytes))
                                .isInstanceOf(ProductCreatedEvent.class).isEqualTo(event);
                    }
                });
    }

    @Test
    void untrustedClassesRemainBlockedWhileAvroDefaultsArePreserved() {
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(originalValidator,
                ClassSecurityValidator.builder().add(java.net.URI.class).build()));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AvroEventSecurityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ClassUtils.forName("java.lang.String")).isEqualTo(String.class);
                    assertThat(ClassUtils.forName("java.net.URI")).isEqualTo(java.net.URI.class);
                    var installedValidator = ClassSecurityValidator.getGlobal();
                    new ApplicationContextRunner()
                            .withConfiguration(AutoConfigurations.of(AvroEventSecurityAutoConfiguration.class))
                            .run(secondContext -> {
                                assertThat(secondContext).hasNotFailed();
                                assertThat(ClassSecurityValidator.getGlobal()).isSameAs(installedValidator);
                            });
                    assertThat(ClassUtils.forName("com.robomart.events.product.ProductCreatedEvent"))
                            .isEqualTo(ProductCreatedEvent.class);
                    assertThatThrownBy(() -> ClassUtils.forName("java.io.File"))
                            .isInstanceOf(SecurityException.class);
                    // Even classes in the events namespace are rejected unless explicitly generated and trusted.
                    assertThatThrownBy(() -> ClassUtils.forName(AvroEventSecurityTest.class.getName()))
                            .isInstanceOf(SecurityException.class);
                });
    }
}
