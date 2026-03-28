package com.robomart.product.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.test.IntegrationTest;

import org.apache.avro.specific.SpecificRecord;

@IntegrationTest
class ProductIndexConsumerIT {

    @Autowired
    private KafkaTemplate<String, SpecificRecord> kafkaTemplate;

    @Autowired
    private ProductSearchRepository productSearchRepository;

    @Test
    void shouldIndexProductWhenCreatedEventReceived() {
        var event = ProductCreatedEvent.newBuilder()
                .setEventId("evt-it-1")
                .setEventType("PRODUCT_CREATED")
                .setAggregateId("100")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(100L)
                .setSku("IT-001")
                .setName("Integration Test Product")
                .setDescription("A test product for integration testing")
                .setPrice("49.99")
                .setCategoryId(1L)
                .setCategoryName("Test Category")
                .setBrand("TestBrand")
                .setRating("4.5")
                .setStockQuantity(25)
                .build();

        kafkaTemplate.send("product.product.created", "100", event);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Optional<ProductDocument> doc = productSearchRepository.findById(100L);
            assertThat(doc).isPresent();
            assertThat(doc.get().getName()).isEqualTo("Integration Test Product");
            assertThat(doc.get().getSku()).isEqualTo("IT-001");
            assertThat(doc.get().getCategoryName()).isEqualTo("Test Category");
        });
    }

    @Test
    void shouldUpdateProductWhenUpdatedEventReceived() {
        // First create
        var createEvent = ProductCreatedEvent.newBuilder()
                .setEventId("evt-it-2a")
                .setEventType("PRODUCT_CREATED")
                .setAggregateId("101")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(101L)
                .setSku("IT-002")
                .setName("Original Name")
                .setPrice("19.99")
                .setCategoryId(1L)
                .setCategoryName("Test")
                .setStockQuantity(10)
                .build();

        kafkaTemplate.send("product.product.created", "101", createEvent);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(productSearchRepository.findById(101L)).isPresent();
        });

        // Then update
        var updateEvent = ProductUpdatedEvent.newBuilder()
                .setEventId("evt-it-2b")
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("101")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(101L)
                .setSku("IT-002")
                .setName("Updated Name")
                .setPrice("24.99")
                .setCategoryId(1L)
                .setCategoryName("Test")
                .setStockQuantity(15)
                .build();

        kafkaTemplate.send("product.product.updated", "101", updateEvent);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Optional<ProductDocument> doc = productSearchRepository.findById(101L);
            assertThat(doc).isPresent();
            assertThat(doc.get().getName()).isEqualTo("Updated Name");
        });
    }

    @Test
    void shouldDeleteProductWhenDeletedEventReceived() {
        // First create
        var createEvent = ProductCreatedEvent.newBuilder()
                .setEventId("evt-it-3a")
                .setEventType("PRODUCT_CREATED")
                .setAggregateId("102")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(102L)
                .setSku("IT-003")
                .setName("To Be Deleted")
                .setPrice("9.99")
                .setCategoryId(1L)
                .setCategoryName("Test")
                .setStockQuantity(5)
                .build();

        kafkaTemplate.send("product.product.created", "102", createEvent);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(productSearchRepository.findById(102L)).isPresent();
        });

        // Then delete
        var deleteEvent = ProductDeletedEvent.newBuilder()
                .setEventId("evt-it-3b")
                .setEventType("PRODUCT_DELETED")
                .setAggregateId("102")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(102L)
                .setSku("IT-003")
                .build();

        kafkaTemplate.send("product.product.deleted", "102", deleteEvent);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(productSearchRepository.findById(102L)).isEmpty();
        });
    }
}
