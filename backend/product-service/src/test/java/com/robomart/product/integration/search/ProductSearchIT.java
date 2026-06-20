package com.robomart.product.integration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.test.ElasticsearchTestSupport;
import com.robomart.test.IntegrationTest;

@IntegrationTest
class ProductSearchIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ProductSearchRepository productSearchRepository;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();

        // Ensure the index exists with the correct field mappings (brand as keyword, etc.).
        // Recreate the index each time to avoid stale mappings from a previous test run
        // (e.g., dynamic mapping creates 'brand' as text, breaking term queries). The reset is
        // race-tolerant: it waits for the delete to propagate and tolerates a concurrent recreate,
        // so it is safe regardless of which test class ran before against the shared ES container.
        ElasticsearchTestSupport.resetIndex(elasticsearchOperations, ProductDocument.class);

        productSearchRepository.saveAll(java.util.List.of(
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

        elasticsearchOperations.indexOps(ProductDocument.class).refresh();

        // Elasticsearch is near-real-time: a refresh() does not guarantee the freshly-indexed docs are
        // immediately visible to a query under a slow/contended CI runner. Wait until the documents are
        // actually queryable — first that all 5 are countable, then via an end-to-end search through the
        // SAME endpoint the tests use — before any @Test runs, so the seed is observable rather than
        // merely written. Use >= 5 (not == 5): count() is itself near-real-time and may transiently lag,
        // and the reset() in setUp guarantees a clean index, so the count converges to exactly 5.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> productSearchRepository.count() >= 5);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> response = search("/api/v1/products/search?keyword=headphone");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
        });
    }

    @AfterEach
    void tearDown() {
        productSearchRepository.deleteAll();
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
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
