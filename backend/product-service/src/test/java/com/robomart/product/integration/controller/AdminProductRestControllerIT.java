package com.robomart.product.integration.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AdminProductRestControllerIT {

    @LocalServerPort
    private int port;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Don't throw on error status codes — we assert them directly
                })
                .build();
    }

    @Test
    void shouldCreateProductWhenValidRequest() {
        String body = """
                {
                  "name": "New Admin Product",
                  "description": "Created via admin API",
                  "categoryId": 1,
                  "price": 99.99,
                  "brand": "AdminBrand",
                  "sku": "ADMIN-IT-001"
                }
                """;

        var response = restClient.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("New Admin Product");
        assertThat(response.getBody()).contains("ADMIN-IT-001");
        assertThat(response.getBody()).contains("\"traceId\"");
    }

    @Test
    void shouldReturn400WhenCreateWithInvalidRequest() {
        String body = """
                {
                  "name": "",
                  "categoryId": 1,
                  "price": -1.00
                }
                """;

        var response = restClient.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturn404WhenProductNotFoundOnUpdate() {
        String body = """
                {
                  "name": "Updated Product",
                  "description": "Updated",
                  "categoryId": 1,
                  "price": 49.99,
                  "brand": "Brand"
                }
                """;

        var response = restClient.put()
                .uri("/api/v1/admin/products/99999")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("99999");
    }

    @Test
    void shouldSoftDeleteProductAndReturn204() {
        // First create a product to delete
        String createBody = """
                {
                  "name": "Product To Delete",
                  "description": "Will be deleted",
                  "categoryId": 1,
                  "price": 19.99,
                  "brand": "Brand",
                  "sku": "DEL-IT-001"
                }
                """;

        var createResponse = restClient.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createBody)
                .retrieve()
                .toEntity(String.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).contains("\"id\"");

        // Extract the created product id
        String responseBody = createResponse.getBody();
        String idStr = responseBody.substring(responseBody.indexOf("\"id\":") + 5);
        long createdId = Long.parseLong(idStr.substring(0, idStr.indexOf(",")));

        // Delete it
        var deleteResponse = restClient.delete()
                .uri("/api/v1/admin/products/" + createdId)
                .retrieve()
                .toEntity(Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify product no longer appears in public listing (active=false)
        var listResponse = restClient.get()
                .uri("/api/v1/products/" + createdId)
                .retrieve()
                .toEntity(String.class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturn404WhenDeletingNonExistentProduct() {
        var response = restClient.delete()
                .uri("/api/v1/admin/products/99999")
                .retrieve()
                .toEntity(Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturnAllCategoriesFromCategoriesEndpoint() {
        var response = restClient.get()
                .uri("/api/v1/admin/categories")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // Seed data has categories — array response
        assertThat(response.getBody()).startsWith("[");
        assertThat(response.getBody()).contains("\"name\"");
    }
}
