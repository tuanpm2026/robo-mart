package com.robomart.product.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.apache.avro.specific.SpecificRecord;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.product.service.ProductIndexWriter;
import com.robomart.product.service.ProductProjectionStore;
import com.robomart.product.service.ProductService;
import com.robomart.test.IntegrationTest;

@IntegrationTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductIndexConsumerIT {
    @Autowired private KafkaTemplate<String, SpecificRecord> kafka;
    @Autowired private ProductSearchRepository search;
    @Autowired private ElasticsearchOperations elastic;
    @Autowired private ProductService products;
    @Autowired private ProductProjectionStore source;
    @Autowired private ProductIndexWriter writer;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private com.robomart.product.service.ProcessedEventService processed;

    @BeforeAll
    void start() { listeners.getListenerContainers().forEach(container -> container.start()); }
    @AfterAll
    void stop() { listeners.getListenerContainers().forEach(container -> container.stop()); }

    private long create(String name) {
        return products.createProduct(new CreateProductRequest(name, null, 1L, new BigDecimal("19.95"),
                "Test", UUID.randomUUID().toString())).id();
    }

    private ProductCreatedEvent created(long id) {
        return ProductCreatedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setEventType("PRODUCT_CREATED")
                .setAggregateId(String.valueOf(id)).setAggregateType("PRODUCT").setTimestamp(Instant.now()).setVersion(1)
                .setProductId(id).setSku("event-preview").setName("Untrusted old snapshot").setPrice("1.00")
                .setCategoryId(1L).setCategoryName("Old category").setStockQuantity(1).build();
    }

    @Test
    void createdEventIndexesTheAuthoritativeDatabaseSnapshot() throws Exception {
        long id = create("Authoritative created name");
        kafka.send("product.product.created", String.valueOf(id), created(id)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(search.findById(id)).hasValueSatisfying(document -> {
                    assertThat(document.getName()).isEqualTo("Authoritative created name");
                    assertThat(document.getPrice()).isEqualByComparingTo("19.95");
                    assertThat(document.getSourceVersion()).isZero();
                }));
    }

    @Test
    void delayedOldSnapshotAndCrossTopicEventsCannotRegressAProduct() throws Exception {
        long id = create("Original name");
        ProductDocument old = source.load(id);
        writer.write(old);
        products.updateProduct(id, new UpdateProductRequest("Current name", null, 1L, new BigDecimal("24.99"), "Test", 0));
        var update = ProductUpdatedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setEventType("PRODUCT_UPDATED")
                .setAggregateId(String.valueOf(id)).setAggregateType("PRODUCT").setTimestamp(Instant.now()).setVersion(1)
                .setProductId(id).setSku("old-preview").setName("Stale event name").setPrice("1.00")
                .setCategoryId(1L).setCategoryName("Old category").setStockQuantity(1).build();
        kafka.send("product.product.updated", String.valueOf(id), update).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(search.findById(id)).hasValueSatisfying(document -> assertThat(document.getName()).isEqualTo("Current name")));
        writer.write(old);
        var late = created(id);
        kafka.send("product.product.created", String.valueOf(id), late).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).until(() -> processed.isProcessed(
                "product-service-product-index-group", late.getEventId()));
        assertThat(search.findById(id)).hasValueSatisfying(document -> assertThat(document.getName()).isEqualTo("Current name"));
    }

    @Test
    void deletionKeepsAVersionedTombstoneAndLateUpdatesCannotResurrectIt() throws Exception {
        long id = create("Deleted product");
        ProductDocument old = source.load(id);
        writer.write(old);
        products.deleteProduct(id);
        var deletion = ProductDeletedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setEventType("PRODUCT_DELETED")
                .setAggregateId(String.valueOf(id)).setAggregateType("PRODUCT").setTimestamp(Instant.now()).setVersion(1)
                .setProductId(id).setSku("old-preview").build();
        kafka.send("product.product.deleted", String.valueOf(id), deletion).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(search.findById(id)).hasValueSatisfying(document -> assertThat(document.isDeleted()).isTrue()));
        writer.write(old);
        var late = created(id);
        kafka.send("product.product.created", String.valueOf(id), late).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).until(() -> processed.isProcessed(
                "product-service-product-index-group", late.getEventId()));
        assertThat(search.findById(id)).hasValueSatisfying(document -> assertThat(document.isDeleted()).isTrue());
        elastic.indexOps(ProductDocument.class).refresh();
        var query = NativeQuery.builder().withQuery(value -> value.term(term -> term.field("sku").value(old.getSku()))).build();
        assertThat(elastic.search(query, ProductDocument.class).getTotalHits()).isZero();
    }
}
