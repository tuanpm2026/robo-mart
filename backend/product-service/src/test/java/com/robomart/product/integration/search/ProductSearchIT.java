package com.robomart.product.integration.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
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

        // Ensure the index exists with the correct field mappings (brand as keyword, etc.)
        // Recreate the index each time to avoid stale mappings from a previous test run
        // (e.g., dynamic mapping creates 'brand' as text, breaking term queries).
        var indexOps = elasticsearchOperations.indexOps(ProductDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
        }
        indexOps.createWithMapping();

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
    }

    @AfterEach
    void tearDown() {
        productSearchRepository.deleteAll();
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
    }

    @Test
    void shouldReturnRelevantProductsWhenKeywordSearch() {
        var response = restClient.get()
                .uri("/api/v1/products/search?keyword=headphone")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
        assertThat(response.getBody()).contains("Wired Gaming Headphone");
        assertThat(response.getBody()).doesNotContain("Robot Building Kit");
        assertThat(response.getBody()).contains("\"traceId\"");
    }

    @Test
    void shouldReturnFilteredResultsWhenMultipleFiltersApplied() {
        var response = restClient.get()
                .uri("/api/v1/products/search?keyword=headphone&minPrice=100&maxPrice=200&brand=Sony&minRating=4&categoryId=1")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
        assertThat(response.getBody()).doesNotContain("Wired Gaming Headphone");
    }

    @Test
    void shouldReturnResultsWhenPartialFiltersApplied() {
        var response = restClient.get()
                .uri("/api/v1/products/search?maxPrice=60")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Portable Bluetooth Speaker");
        assertThat(response.getBody()).contains("Remote Control Car");
        assertThat(response.getBody()).doesNotContain("Wireless Bluetooth Headphone");
    }

    @Test
    void shouldReturnResultsWhenFilterByCategoryId() {
        var response = restClient.get()
                .uri("/api/v1/products/search?categoryId=2")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Robot Building Kit");
        assertThat(response.getBody()).contains("Remote Control Car");
        assertThat(response.getBody()).doesNotContain("Wireless Bluetooth Headphone");
    }

    @Test
    void shouldReturnResultsWhenFilterByBrandOnly() {
        var response = restClient.get()
                .uri("/api/v1/products/search?brand=Sony")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
        assertThat(response.getBody()).contains("Remote Control Car");
        assertThat(response.getBody()).doesNotContain("Robot Building Kit");
    }

    @Test
    void shouldReturnResultsWhenFilterByMinRating() {
        var response = restClient.get()
                .uri("/api/v1/products/search?minRating=4.5")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Wireless Bluetooth Headphone");
        assertThat(response.getBody()).contains("Robot Building Kit");
        assertThat(response.getBody()).doesNotContain("Portable Bluetooth Speaker");
    }

    @Test
    void shouldReturnEmptyResultsWhenNoMatchingProducts() {
        var response = restClient.get()
                .uri("/api/v1/products/search?keyword=nonexistent+product+xyz123")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\":[]");
        assertThat(response.getBody()).contains("\"totalElements\":0");
        assertThat(response.getBody()).contains("\"totalPages\":0");
    }

    @Test
    void shouldReturnCorrectPaginationMetadata() {
        var response = restClient.get()
                .uri("/api/v1/products/search?page=0&size=2")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"size\":2");
        assertThat(response.getBody()).contains("\"totalElements\":5");
        assertThat(response.getBody()).contains("\"totalPages\":3");
        assertThat(response.getBody()).contains("\"page\":0");
    }

    @Test
    void shouldReturnAllProductsWhenNoFiltersProvided() {
        var response = restClient.get()
                .uri("/api/v1/products/search")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"totalElements\":5");
    }

    @Test
    void shouldReturnDefaultPaginationWhenNoPageParams() {
        var response = restClient.get()
                .uri("/api/v1/products/search?keyword=headphone")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"size\":20");
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
