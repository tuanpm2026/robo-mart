package com.robomart.cart.integration.event;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.robomart.cart.event.consumer.ProductEventConsumer;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class CartPriceUpdateIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ProductEventConsumer productEventConsumer;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @Test
    void shouldUpdateCartPriceWhenProductPriceChanges() {
        String cartId = "price-update-test-" + System.nanoTime();

        // Create cart with a product at original price
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 42, "productName": "Test Product", "price": 29.99, "quantity": 2}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).contains("\"price\":29.99");

        // Simulate product price change event
        var event = ProductUpdatedEvent.newBuilder()
                .setEventId("test-evt-price-" + System.nanoTime())
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("42")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(42L)
                .setSku("SKU-042")
                .setName("Test Product Updated")
                .setPrice("39.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();

        productEventConsumer.onProductUpdated(event);

        // Retrieve cart and verify updated price
        var getResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).contains("\"price\":39.99");
        assertThat(getResponse.getBody()).contains("\"Test Product Updated\"");
    }

    @Test
    void shouldUpdatePriceInMultipleItemCart() {
        String cartId = "multi-item-price-test-" + System.nanoTime();

        // Add two different products
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 10, "productName": "Product A", "price": 10.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 20, "productName": "Product B", "price": 20.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Update price of product 10 only
        var event = ProductUpdatedEvent.newBuilder()
                .setEventId("test-evt-multi-" + System.nanoTime())
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("10")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(10L)
                .setSku("SKU-010")
                .setName("Product A Updated")
                .setPrice("15.00")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();

        productEventConsumer.onProductUpdated(event);

        // Verify cart has updated price for product 10 but product 20 unchanged
        var getResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).contains("\"Product A Updated\"");
        assertThat(getResponse.getBody()).contains("\"Product B\"");
        // Total should be 15.00 + 20.00 = 35.00
        assertThat(getResponse.getBody()).contains("\"totalPrice\":35.00");
    }

    @Test
    void shouldNotAffectCartWhenUnrelatedProductUpdated() {
        String cartId = "unrelated-price-test-" + System.nanoTime();

        // Create cart with product 50
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 50, "productName": "My Product", "price": 25.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Update a different product (product 99)
        var event = ProductUpdatedEvent.newBuilder()
                .setEventId("test-evt-unrelated-" + System.nanoTime())
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("99")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(99L)
                .setSku("SKU-099")
                .setName("Other Product")
                .setPrice("50.00")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();

        productEventConsumer.onProductUpdated(event);

        // Cart should be unchanged
        var getResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).contains("\"My Product\"");
        assertThat(getResponse.getBody()).contains("\"price\":25.00");
    }
}
