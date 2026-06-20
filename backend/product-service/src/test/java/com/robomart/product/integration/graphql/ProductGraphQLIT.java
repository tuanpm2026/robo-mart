package com.robomart.product.integration.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

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
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.client.RestClient;

import com.robomart.product.config.CacheConfig;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.test.ElasticsearchTestSupport;
import com.robomart.test.IntegrationTest;

/**
 * All test methods here read the same immutable 3-document dataset, so the index is reset and seeded
 * ONCE per class. The {@code products(...)} GraphQL query is backed by the same {@code @Cacheable}
 * ES-search service as the REST {@code /search} endpoint and shares the same reused Redis container, so
 * this class both clears stale cache entries before seeding and clears its own afterwards — otherwise a
 * cached {@code keyword=headphone} page from here (identical cache key) would leak into ProductSearchIT.
 *
 * <p>Like ProductSearchIT, this class also pauses the live {@link
 * com.robomart.product.event.consumer.ProductIndexConsumer} for its lifetime so sibling classes' async
 * Kafka events cannot index foreign documents into the shared {@code products} index mid-test.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductGraphQLIT {

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

        // Pause the ES-index-writing consumer before reset+seed so sibling classes' async events cannot
        // index foreign documents into the shared index while this class runs.
        setIndexConsumerRunning(false);

        // Reset the shared ES index with the correct mapping before seeding, so this class does not
        // depend on (or leave behind) a dynamically-mapped index when sharing the reused container.
        ElasticsearchTestSupport.resetIndex(elasticsearchOperations, ProductDocument.class);

        // RefreshPolicy.IMMEDIATE forces the bulk index to refresh, so the docs are queryable the instant
        // save() returns — removing the write-side near-real-time gap.
        elasticsearchOperations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(java.util.List.of(
                createDoc(1L, "ELEC-001", "Wireless Bluetooth Headphone",
                        "Premium noise-cancelling headphone",
                        1L, "Electronics", "Sony",
                        BigDecimal.valueOf(149.99), BigDecimal.valueOf(4.5), 50),
                createDoc(2L, "ELEC-002", "Wired Gaming Headphone",
                        "Professional gaming headphone with mic",
                        1L, "Electronics", "SteelSeries",
                        BigDecimal.valueOf(89.99), BigDecimal.valueOf(4.2), 30),
                createDoc(3L, "TOY-001", "Robot Building Kit",
                        "Educational robot toy for kids",
                        2L, "Toys", "LEGO",
                        BigDecimal.valueOf(79.99), BigDecimal.valueOf(4.8), 25)
        ));

        // Clear any stale @Cacheable search results (shared Redis, 60s TTL, key shared with ProductSearchIT)
        // so the queries below recompute against the now-fully-visible index instead of hitting a cache miss
        // cached as empty.
        clearSearchCaches();

        // Backstop: confirm the docs are observable end-to-end through the SAME /graphql endpoint the tests
        // use before any @Test runs. The products(...) response shows JPA-entity names looked up by the
        // ES-matched ids, so we assert on a non-zero totalElements (proof ES served matches) rather than on
        // any seeded ES document name. Use >= 3 because count() is itself near-real-time.
        await().atMost(Duration.ofSeconds(30))
                .until(() -> productSearchRepository.count() >= 3);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var response = postGraphQL("""
                    { products(keyword: "headphone") { content { id name } totalElements } }
                    """);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).doesNotContain("\"totalElements\":0");
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

    @Test
    void shouldReturnProductWithNestedDataWhenQueryById() {
        var response = postGraphQL("""
                { product(id: 1) { id name price category { name } images { imageUrl } } }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("\"product\"");
        assertThat(response.getBody()).contains("\"category\"");
        assertThat(response.getBody()).contains("\"images\"");
    }

    @Test
    void shouldReturnNullWhenProductNotFound() {
        var response = postGraphQL("""
                { product(id: 99999) { id name } }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"product\":null");
    }

    @Test
    void shouldReturnFilteredProductsWhenKeywordSearch() {
        // products(...) is backed by the near-real-time ES search; retry the query until the indexed
        // docs are served, so the visibility race cannot intermittently fail the assertions.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var response = postGraphQL("""
                    { products(keyword: "headphone") { content { id name } totalElements } }
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"products\"");
            assertThat(response.getBody()).contains("\"content\"");
            assertThat(response.getBody()).contains("\"totalElements\"");
        });
    }

    @Test
    void shouldReturnProductsWithMultipleFilters() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var response = postGraphQL("""
                    { products(categoryId: 1, minPrice: 100) { content { id name price } totalElements } }
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"products\"");
            assertThat(response.getBody()).contains("\"content\"");
        });
    }

    @Test
    void shouldReturnEmptyContentWhenNoMatchingProducts() {
        var response = postGraphQL("""
                { products(keyword: "nonexistent_xyz_99999") { content { id } totalElements } }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"content\":[]");
        assertThat(response.getBody()).contains("\"totalElements\":0");
    }

    @Test
    void shouldFollowNativeGraphQLResponseFormat() {
        var response = postGraphQL("""
                { product(id: 1) { id name } }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\"");
        // Native GraphQL format — no REST API wrapper fields
        assertThat(response.getBody()).doesNotContain("\"traceId\"");
        assertThat(response.getBody()).doesNotContain("\"pagination\"");
    }

    private org.springframework.http.ResponseEntity<String> postGraphQL(String query) {
        String escapedQuery = query.replace("\"", "\\\"").replace("\n", " ");
        String body = "{\"query\": \"" + escapedQuery + "\"}";

        return restClient.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
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
