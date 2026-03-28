package com.robomart.product.integration.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ProductRestControllerIT {

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
    void shouldReturnPaginatedProductsWhenGetAll() {
        var response = restClient.get()
                .uri("/api/v1/products?page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("\"pagination\"");
        assertThat(response.getBody()).contains("\"traceId\"");
    }

    @Test
    void shouldReturnFilteredProductsWhenCategoryIdProvided() {
        var response = restClient.get()
                .uri("/api/v1/products?categoryId=1&page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"data\"");
    }

    @Test
    void shouldReturnProductDetailWhenValidId() {
        var response = restClient.get()
                .uri("/api/v1/products/1")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("\"traceId\"");
    }

    @Test
    void shouldReturn404WhenProductNotFound() {
        var response = restClient.get()
                .uri("/api/v1/products/99999")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("PRODUCT_NOT_FOUND");
        assertThat(response.getBody()).contains("99999");
        assertThat(response.getBody()).contains("\"traceId\"");
    }

    @Test
    void shouldRespectPaginationParameters() {
        var response = restClient.get()
                .uri("/api/v1/products?page=0&size=5")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"size\":5");
    }

    @Test
    void shouldReturnDefaultPaginationWhenNoParams() {
        var response = restClient.get()
                .uri("/api/v1/products")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"size\":20");
    }
}
