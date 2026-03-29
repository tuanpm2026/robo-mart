package com.robomart.cart.integration;

import java.math.BigDecimal;

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
class CartIntegrationTest {

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
    void shouldCreateCartAndAddItemWhenNoCartIdProvided() {
        var response = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Test Product", "price": 29.99, "quantity": 2}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Cart-Id")).isNotNull().isNotBlank();
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("\"traceId\"");
        assertThat(response.getBody()).contains("\"cartId\"");
        assertThat(response.getBody()).contains("\"totalItems\":2");
        assertThat(response.getBody()).contains("\"Test Product\"");
    }

    @Test
    void shouldAddItemToExistingCartWhenCartIdProvided() {
        // Create cart first
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Product A", "price": 10.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");

        // Add second item
        var response = restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 2, "productName": "Product B", "price": 20.00, "quantity": 3}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"totalItems\":4");
    }

    @Test
    void shouldReturnCartSummaryWhenGetCart() {
        // Create cart with item
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 10, "productName": "Widget", "price": 15.50, "quantity": 3}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");

        // Get cart
        var response = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"cartId\"");
        assertThat(response.getBody()).contains("\"Widget\"");
        assertThat(response.getBody()).contains("\"totalItems\":3");
        assertThat(response.getBody()).contains("\"traceId\"");
    }

    @Test
    void shouldUpdateItemQuantityWhenValidRequest() {
        // Create cart
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 5, "productName": "Gadget", "price": 25.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");

        // Update quantity
        var response = restClient.put()
                .uri("/api/v1/cart/items/5")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"quantity": 4}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"totalItems\":4");
    }

    @Test
    void shouldRemoveItemFromCartWhenDeleteRequest() {
        // Create cart with two items
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Item A", "price": 10.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");

        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 2, "productName": "Item B", "price": 20.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Remove first item
        var deleteResponse = restClient.delete()
                .uri("/api/v1/cart/items/1")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify cart has only one item
        var getResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(getResponse.getBody()).contains("\"totalItems\":1");
        assertThat(getResponse.getBody()).contains("\"Item B\"");
    }

    @Test
    void shouldPersistCartAcrossRequests() {
        // Create cart
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 42, "productName": "Persistent Item", "price": 99.99, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");

        // Retrieve cart using same ID (simulates page refresh)
        var response = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"Persistent Item\"");
        assertThat(response.getBody()).contains("\"productId\":42");
    }

    @Test
    void shouldReturn404WhenGetNonExistentCart() {
        var response = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", "nonexistent-cart-id")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("CART_NOT_FOUND");
    }

    @Test
    void shouldReturn400WhenAddItemWithInvalidData() {
        var response = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": null, "productName": "", "price": -1, "quantity": 0}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturn404WhenUpdatingNonExistentCartItem() {
        // Create cart
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Item", "price": 10.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");

        // Update non-existent item
        var response = restClient.put()
                .uri("/api/v1/cart/items/9999")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"quantity": 5}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("CART_ITEM_NOT_FOUND");
    }
}
