package com.robomart.product.integration.graphql;

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
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.test.IntegrationTest;

@IntegrationTest
class ProductGraphQLIT {

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

        productSearchRepository.saveAll(java.util.List.of(
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

        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
    }

    @AfterEach
    void tearDown() {
        productSearchRepository.deleteAll();
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
        var response = postGraphQL("""
                { products(keyword: "headphone") { content { id name } totalElements } }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"products\"");
        assertThat(response.getBody()).contains("\"content\"");
        assertThat(response.getBody()).contains("\"totalElements\"");
    }

    @Test
    void shouldReturnProductsWithMultipleFilters() {
        var response = postGraphQL("""
                { products(categoryId: 1, minPrice: 100) { content { id name price } totalElements } }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"products\"");
        assertThat(response.getBody()).contains("\"content\"");
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
