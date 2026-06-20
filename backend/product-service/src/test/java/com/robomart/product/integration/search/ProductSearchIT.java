package com.robomart.product.integration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.client.RestClient;

import com.robomart.product.config.CacheConfig;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.test.ElasticsearchTestSupport;
import com.robomart.test.IntegrationTest;

/**
 * All test methods here are read-only searches over the same immutable 5-document dataset, so the index
 * is reset and seeded ONCE per class (in {@link #seedOnce()}), not per method. Seeding once is faster
 * and collapses the per-method reset/reseed race windows down to a single one.
 *
 * <p>Critically, the {@code products} Elasticsearch index is <em>shared and reused</em> across all
 * product-service IT classes (same JVM, same Testcontainer). The live {@link
 * com.robomart.product.event.consumer.ProductIndexConsumer} Kafka listener indexes products that OTHER
 * test classes (e.g. AdminProductRestControllerIT) create — and under full-reactor contention those
 * events arrive asynchronously and late, landing in the middle of THIS class and inflating the document
 * count (e.g. an exact-count assertion saw {@code totalElements:7} instead of 5). To make the dataset
 * deterministic, the index-writing consumer is paused for the lifetime of this class and resumed in
 * {@code @AfterAll}, so the only writer of the shared index here is this test's own seed.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductSearchIT {

    /** Consumer group of {@link com.robomart.product.event.consumer.ProductIndexConsumer} (the only ES-index writer). */
    private static final String INDEX_CONSUMER_GROUP = "product-service-product-index-group";

    @LocalServerPort
    private int port;

    @Autowired
    private ProductSearchRepository productSearchRepository;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerRegistry;

    private RestClient restClient;

    @BeforeAll
    void seedOnce() {
        restClient = restClient();

        // Stop the ProductIndexConsumer first, so async PRODUCT_CREATED/UPDATED/DELETED events emitted by
        // sibling test classes can no longer index foreign documents into the shared index while this class
        // runs. This must happen BEFORE the reset+seed so the seeded count stays exactly 5.
        setIndexConsumerRunning(false);

        // Ensure the index exists with the correct field mappings (brand as keyword, etc.).
        // Recreate the index to avoid stale mappings from a previous test run (e.g., dynamic mapping
        // creates 'brand' as text, breaking term queries). The reset is race-tolerant: it waits for the
        // delete to propagate and tolerates a concurrent recreate, so it is safe regardless of which test
        // class ran before against the shared ES container.
        ElasticsearchTestSupport.resetIndex(elasticsearchOperations, ProductDocument.class);

        // Save with RefreshPolicy.IMMEDIATE so the bulk index forces a refresh: the documents are
        // guaranteed queryable the instant save() returns, removing the near-real-time write-side gap
        // (a plain saveAll + refresh() can still lag the *query* path under CI contention).
        elasticsearchOperations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(java.util.List.of(
                createDoc(1L, "ELEC-001", "Wireless Bluetooth Headphone", "Premium noise-cancelling headphone",
                        1L, "Electronics", "Sony", BigDecimal.valueOf(149.99), BigDecimal.valueOf(4.5), 50),
                createDoc(2L, "ELEC-002", "Wired Gaming Headphone", "Professional gaming headphone with mic",
                        1L, "Electronics", "SteelSeries", BigDecimal.valueOf(89.99), BigDecimal.valueOf(4.2), 30),
                createDoc(3L, "ELEC-003", "Portable Bluetooth Speaker", "Waterproof outdoor speaker",
                        1L, "Electronics", "JBL", BigDecimal.valueOf(59.99), BigDecimal.valueOf(4.0), 100),
                createDoc(4L, "TOY-001", "Robot Building Kit", "Educational robot toy for kids",
                        2L, "Toys", "LEGO", BigDecimal.valueOf(79.99), BigDecimal.valueOf(4.8), 25),
                createDoc(5L, "TOY-002", "Remote Control Car", "High-speed RC car",
                        2L, "Toys", "Sony", BigDecimal.valueOf(45.99), BigDecimal.valueOf(3.5), 60)
        ));

        // ProductSearchService.search() is @Cacheable in the shared, reused Redis container. A previous
        // test class (e.g. ProductGraphQLIT, which searches the same keyword=headphone => identical cache
        // key) or a transient earlier near-real-time miss can leave a STALE entry — including an empty
        // {data:[],totalElements:0} page — pinned for the 60s TTL. Without this clear, every query below
        // is a cache HIT that never re-queries ES, so the search returns empty even though count() (which
        // is uncached and live) sees all 5 docs. Clearing here guarantees the first query per key is
        // recomputed against the now-fully-visible index.
        clearSearchCaches();

        // Backstop: confirm the documents are observable end-to-end through the SAME endpoint the tests
        // use before any @Test runs. With IMMEDIATE refresh + cache clear this passes on the first poll;
        // the wait only absorbs extreme CI slowness. 30s is a generous ceiling, not the primary fix.
        await().atMost(Duration.ofSeconds(30))
                .until(() -> productSearchRepository.count() >= 5);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ResponseEntity<String> response = search("/api/v1/products/search?keyword=headphone");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
        });
    }

    @BeforeEach
    void setUp() {
        restClient = restClient();
    }

    @AfterAll
    void tearDownAll() {
        productSearchRepository.deleteAll();
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
        // Don't leave our cached search results behind to poison another class sharing the Redis container.
        clearSearchCaches();
        // Resume the index consumer so later classes (e.g. ProductIndexConsumerIT) work normally.
        setIndexConsumerRunning(true);
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();
    }

    /**
     * Starts or stops the {@link com.robomart.product.event.consumer.ProductIndexConsumer} listener
     * containers (matched by consumer group), so this read-only search class can own the shared ES index
     * for its lifetime without sibling classes' async events mutating it. {@code stop()} is synchronous,
     * so any in-flight index write completes before we return.
     */
    private void setIndexConsumerRunning(boolean running) {
        kafkaListenerRegistry.getListenerContainers().stream()
                .filter(c -> INDEX_CONSUMER_GROUP.equals(c.getGroupId()))
                .forEach(c -> {
                    if (running) {
                        c.start();
                    } else {
                        c.stop();
                    }
                });
    }

    private void clearSearchCaches() {
        var searchCache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH);
        if (searchCache != null) {
            searchCache.clear();
        }
        var detailCache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL);
        if (detailCache != null) {
            detailCache.clear();
        }
    }

    private ResponseEntity<String> search(String uri) {
        return restClient.get()
                .uri(uri)
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * Runs a search request and applies {@code assertions}, retrying the whole request until they pass
     * (up to 10s). Elasticsearch's near-real-time visibility race can recur per query under CI load, so
     * a single freshly-seeded query may transiently return fewer/no docs; retrying the query — not
     * weakening the checks — makes the assertions robust to indexing latency.
     */
    private void awaitSearch(String uri, Consumer<ResponseEntity<String>> assertions) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertions.accept(search(uri)));
    }

    @Test
    void shouldReturnRelevantProductsWhenKeywordSearch() {
        awaitSearch("/api/v1/products/search?keyword=headphone", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"data\"");
            assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
            assertThat(response.getBody()).contains("Wired Gaming Headphone");
            assertThat(response.getBody()).doesNotContain("Robot Building Kit");
            assertThat(response.getBody()).contains("\"traceId\"");
        });
    }

    @Test
    void shouldReturnFilteredResultsWhenMultipleFiltersApplied() {
        awaitSearch("/api/v1/products/search?keyword=headphone&minPrice=100&maxPrice=200&brand=Sony&minRating=4&categoryId=1",
                response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
                    assertThat(response.getBody()).doesNotContain("Wired Gaming Headphone");
                });
    }

    @Test
    void shouldReturnResultsWhenPartialFiltersApplied() {
        awaitSearch("/api/v1/products/search?maxPrice=60", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Portable Bluetooth Speaker");
            assertThat(response.getBody()).contains("Remote Control Car");
            assertThat(response.getBody()).doesNotContain("Wireless Bluetooth Headphone");
        });
    }

    @Test
    void shouldReturnResultsWhenFilterByCategoryId() {
        awaitSearch("/api/v1/products/search?categoryId=2", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Robot Building Kit");
            assertThat(response.getBody()).contains("Remote Control Car");
            assertThat(response.getBody()).doesNotContain("Wireless Bluetooth Headphone");
        });
    }

    @Test
    void shouldReturnResultsWhenFilterByBrandOnly() {
        awaitSearch("/api/v1/products/search?brand=Sony", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
            assertThat(response.getBody()).contains("Remote Control Car");
            assertThat(response.getBody()).doesNotContain("Robot Building Kit");
        });
    }

    @Test
    void shouldReturnResultsWhenFilterByMinRating() {
        awaitSearch("/api/v1/products/search?minRating=4.5", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
            assertThat(response.getBody()).contains("Robot Building Kit");
            assertThat(response.getBody()).doesNotContain("Portable Bluetooth Speaker");
        });
    }

    @Test
    void shouldReturnEmptyResultsWhenNoMatchingProducts() {
        awaitSearch("/api/v1/products/search?keyword=nonexistent+product+xyz123", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"data\":[]");
            assertThat(response.getBody()).contains("\"totalElements\":0");
            assertThat(response.getBody()).contains("\"totalPages\":0");
        });
    }

    @Test
    void shouldReturnCorrectPaginationMetadata() {
        awaitSearch("/api/v1/products/search?page=0&size=2", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"size\":2");
            assertThat(response.getBody()).contains("\"totalElements\":5");
            assertThat(response.getBody()).contains("\"totalPages\":3");
            assertThat(response.getBody()).contains("\"page\":0");
        });
    }

    @Test
    void shouldReturnAllProductsWhenNoFiltersProvided() {
        awaitSearch("/api/v1/products/search", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"totalElements\":5");
        });
    }

    @Test
    void shouldReturnDefaultPaginationWhenNoPageParams() {
        awaitSearch("/api/v1/products/search?keyword=headphone", response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"size\":20");
        });
    }

    @Test
    void shouldRejectRequestWhenMinPriceGreaterThanMaxPrice() {
        var response = restClient.get()
                .uri("/api/v1/products/search?minPrice=200&maxPrice=50")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    private ProductDocument createDoc(Long id, String sku, String name, String description,
                                       Long categoryId, String categoryName, String brand,
                                       BigDecimal price, BigDecimal rating, int stockQuantity) {
        var doc = new ProductDocument();
        doc.setId(id);
        doc.setSku(sku);
        doc.setName(name);
        doc.setDescription(description);
        doc.setCategoryId(categoryId);
        doc.setCategoryName(categoryName);
        doc.setBrand(brand);
        doc.setPrice(price);
        doc.setRating(rating);
        doc.setStockQuantity(stockQuantity);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
