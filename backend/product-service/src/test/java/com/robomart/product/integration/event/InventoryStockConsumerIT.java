package com.robomart.product.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import com.robomart.events.inventory.StockUpdatedEvent;
import com.robomart.product.event.consumer.InventoryStockConsumer;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductSearchRequest;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.product.service.OutboxPollingService;
import com.robomart.product.service.ProductIndexWriter;
import com.robomart.product.service.ProductProjectionStore;
import com.robomart.product.service.ProductSearchService;
import com.robomart.product.service.ProductService;
import com.robomart.test.ProductIntegrationTest;

@ProductIntegrationTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryStockConsumerIT {
    @Autowired private InventoryStockConsumer consumer;
    @Autowired private ProductService products;
    @Autowired private ProductRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaTemplate<String, SpecificRecord> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private OutboxPollingService poller;
    @Autowired private ProductIndexWriter writer;
    @Autowired private ProductProjectionStore source;
    @Autowired private ProductSearchRepository search;
    @Autowired private ProductSearchService searching;
    @Autowired private ElasticsearchOperations elastic;

    @BeforeAll
    void start() { listeners.getListenerContainers().forEach(container -> container.start()); }

    @AfterAll
    void stop() { listeners.getListenerContainers().forEach(container -> container.stop()); }

    private long create(String name) {
        return products.createProduct(new CreateProductRequest(name, null, 1L, new BigDecimal("19.95"),
                "StockTest", UUID.randomUUID().toString())).id();
    }

    private StockUpdatedEvent snapshot(long id, int quantity, long version) {
        return StockUpdatedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setEventType("STOCK_UPDATED")
                .setAggregateId(Long.toString(id)).setAggregateType("InventoryItem")
                .setTimestamp(Instant.now()).setVersion(1).setProductId(Long.toString(id))
                .setAvailableQuantity(quantity).setInventoryVersion(version).build();
    }

    private long updates(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? "
                + "AND event_type='PRODUCT_UPDATED'", Long.class, Long.toString(id));
    }

    @Test
    void duplicateAndDelayedSnapshotsCannotRegressStockOrCreateMoreProductEvents() {
        long id = create("Stock fencing");
        var latest = snapshot(id, 21, 8);
        consumer.onStockUpdated(latest);
        int version = repository.findById(id).orElseThrow().getVersion();
        consumer.onStockUpdated(latest);
        consumer.onStockUpdated(snapshot(id, 99, 7));
        consumer.onStockUpdated(snapshot(id, 99, 8));
        var product = repository.findById(id).orElseThrow();
        assertThat(product.getStockQuantity()).isEqualTo(21);
        assertThat(product.getInventoryVersion()).isEqualTo(8);
        assertThat(product.getVersion()).isEqualTo(version);
        assertThat(updates(id)).isEqualTo(1);
        products.updateProduct(id, new UpdateProductRequest("Stock fencing edited", null, 1L,
                new BigDecimal("20.95"), "StockTest", version));
        assertThat(repository.findById(id).orElseThrow().getInventoryVersion()).isEqualTo(8);
        assertThat(repository.findById(id).orElseThrow().getStockQuantity()).isEqualTo(21);
    }

    @Test
    void concurrentSnapshotsConvergeOnTheNewestInventoryVersion() throws Exception {
        long id = create("Concurrent stock");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var older = workers.submit(() -> consumer.onStockUpdated(snapshot(id, 10, 1)));
            var newer = workers.submit(() -> consumer.onStockUpdated(snapshot(id, 20, 2)));
            older.get(10, TimeUnit.SECONDS);
            newer.get(10, TimeUnit.SECONDS);
        }
        assertThat(repository.findById(id).orElseThrow().getInventoryVersion()).isEqualTo(2);
        assertThat(repository.findById(id).orElseThrow().getStockQuantity()).isEqualTo(20);
    }

    @Test
    void failedProductEventWriteRollsBackTheStockSnapshot() {
        long id = create("Atomic stock projection");
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_stock_product_update CHECK "
                + "(event_type <> 'PRODUCT_UPDATED' OR aggregate_id <> '" + id + "') NOT VALID");
        try {
            assertThatThrownBy(() -> consumer.onStockUpdated(snapshot(id, 21, 8)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var product = repository.findById(id).orElseThrow();
            assertThat(product.getStockQuantity()).isZero();
            assertThat(product.getInventoryVersion()).isEqualTo(-1);
            assertThat(updates(id)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_stock_product_update");
        }
        consumer.onStockUpdated(snapshot(id, 21, 8));
        assertThat(repository.findById(id).orElseThrow().getStockQuantity()).isEqualTo(21);
    }

    @Test
    void kafkaStockDeliveryRefreshesCatalogSearchAndAlreadyPopulatedCaches() throws Exception {
        String name = "Stock" + UUID.randomUUID().toString().replace("-", "");
        long id = create(name);
        writer.writeVisible(source.load(id));
        elastic.indexOps(ProductDocument.class).refresh();
        var query = new ProductSearchRequest(name, null, null, null, null, null);
        assertThat(products.getProductById(id).stockQuantity()).isZero();
        assertThat(searching.search(query, PageRequest.of(0, 10)).data()).hasSize(1)
                .allSatisfy(product -> assertThat(product.stockQuantity()).isZero());

        kafka.send("inventory.stock.updated", Long.toString(id), snapshot(id, 21, 8)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            poller.pollAndPublish();
            assertThat(products.getProductById(id).stockQuantity()).isEqualTo(21);
            assertThat(search.findById(id)).hasValueSatisfying(document ->
                    assertThat(document.getStockQuantity()).isEqualTo(21));
            assertThat(searching.search(query, PageRequest.of(0, 10)).data()).hasSize(1)
                    .allSatisfy(product -> assertThat(product.stockQuantity()).isEqualTo(21));
        });
    }

    @Test
    void invalidSnapshotDoesNotChangeStock() {
        long id = create("Invalid stock");
        assertThatThrownBy(() -> consumer.onStockUpdated(snapshot(id, -1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onStockUpdated(snapshot(id, 1, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findById(id).orElseThrow().getInventoryVersion()).isEqualTo(-1);
        assertThat(updates(id)).isZero();
    }
}
