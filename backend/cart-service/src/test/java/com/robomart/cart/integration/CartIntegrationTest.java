package com.robomart.cart.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import com.robomart.test.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@TestPropertySource(properties = "robomart.cart.ttl-minutes=1")
class CartIntegrationTest {

    @LocalServerPort
    private int port;

    private RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Don't throw on error status codes — we assert them directly
                })
                .build();
    }

    // === Story 2.1 tests (unchanged behavior) ===

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

    // === Story 2.2: Cart Persistence by userId ===

    @Test
    void shouldPersistCartByUserId() {
        String userId = "user-persist-test";

        // Create cart with userId
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 100, "productName": "User Cart Item", "price": 49.99, "quantity": 2}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Retrieve with same userId (simulates new session)
        var response = restClient.get()
                .uri("/api/v1/cart")
                .header("X-User-Id", userId)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"User Cart Item\"");
        assertThat(response.getBody()).contains("\"totalItems\":2");
    }

    @Test
    void shouldReturnSameCartForSameUserId() {
        String userId = "user-accumulate-test";

        // Add first item
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Item A", "price": 10.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Add second item (same userId, new request)
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 2, "productName": "Item B", "price": 20.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Verify both items in same cart
        var response = restClient.get()
                .uri("/api/v1/cart")
                .header("X-User-Id", userId)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"Item A\"");
        assertThat(response.getBody()).contains("\"Item B\"");
        assertThat(response.getBody()).contains("\"totalItems\":2");
    }

    @Test
    void shouldMaintainAnonymousCartBehavior() {
        // Create anonymous cart (no X-User-Id)
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 50, "productName": "Anon Item", "price": 15.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");
        assertThat(cartId).isNotNull().isNotBlank();

        // Retrieve with X-Cart-Id (no X-User-Id)
        var response = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"Anon Item\"");
    }

    // === Story 2.2: TTL Expiry ===
    // NOTE: getCart() saves the cart to reset TTL. Use Thread.sleep() instead of
    // Awaitility polling (which would keep resetting TTL via GET requests).

    @Test
    void shouldExpireCartAfterTtl() throws InterruptedException {
        // TTL is set to 1 minute via @TestPropertySource
        // Do NOT call GET after creation — it would reset TTL
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 99, "productName": "Expiring Item", "price": 5.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Wait for TTL expiry (60s + 10s buffer)
        Thread.sleep(70_000);

        // Single check — cart should be expired
        var expiredResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);
        assertThat(expiredResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // === Story 3.4: Cart Merge on Login ===

    @Test
    void shouldMergeAnonymousCartIntoAuthenticatedUserCart() {
        String anonymousId = "anon-merge-test-" + System.nanoTime();
        String userId = "user-merge-test-" + System.nanoTime();

        // Create anonymous cart with 2 items
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", anonymousId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Anon Item A", "price": 10.00, "quantity": 2}
                        """)
                .retrieve()
                .toEntity(String.class);

        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", anonymousId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 2, "productName": "Anon Item B", "price": 20.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Create authenticated user cart with 1 item
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 3, "productName": "Auth Item C", "price": 30.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Merge
        var mergeResponse = restClient.post()
                .uri("/api/v1/cart/merge")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"anonymousCartId\": \"" + anonymousId + "\"}")
                .retrieve()
                .toEntity(String.class);

        assertThat(mergeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode mergeData = jsonMapper.readTree(mergeResponse.getBody()).get("data");
        assertThat(mergeData.get("totalItems").intValue()).isEqualTo(4);
        assertThat(mergeData.get("items").toString())
                .contains("Anon Item A", "Anon Item B", "Auth Item C");

        // Verify anonymous cart is deleted
        var anonCartResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", anonymousId)
                .retrieve()
                .toEntity(String.class);

        assertThat(anonCartResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldSumDuplicateProductQuantitiesOnMerge() {
        String anonymousId = "anon-dup-test-" + System.nanoTime();
        String userId = "user-dup-test-" + System.nanoTime();

        // Same product in both carts
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", anonymousId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Shared Product", "price": 10.00, "quantity": 3}
                        """)
                .retrieve()
                .toEntity(String.class);

        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Shared Product", "price": 10.00, "quantity": 2}
                        """)
                .retrieve()
                .toEntity(String.class);

        var mergeResponse = restClient.post()
                .uri("/api/v1/cart/merge")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"anonymousCartId\": \"" + anonymousId + "\"}")
                .retrieve()
                .toEntity(String.class);

        assertThat(mergeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode dupData = jsonMapper.readTree(mergeResponse.getBody()).get("data");
        assertThat(dupData.get("totalItems").intValue()).isEqualTo(5);
    }

    @Test
    void shouldReturnUserCartWhenAnonymousCartNotFoundOnMerge() {
        String userId = "user-noanon-test-" + System.nanoTime();

        // Create authenticated cart
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Existing Item", "price": 10.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        // Merge with non-existent anonymous cart
        var mergeResponse = restClient.post()
                .uri("/api/v1/cart/merge")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"anonymousCartId": "nonexistent-anon-id"}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(mergeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode noAnonData = jsonMapper.readTree(mergeResponse.getBody()).get("data");
        assertThat(noAnonData.get("totalItems").intValue()).isEqualTo(1);
        assertThat(noAnonData.get("items").toString()).contains("Existing Item");
    }

    @Test
    void shouldHandleIdempotentMergeCalls() {
        String anonymousId = "anon-idempotent-" + System.nanoTime();
        String userId = "user-idempotent-" + System.nanoTime();

        // Create anonymous cart
        restClient.post()
                .uri("/api/v1/cart/items")
                .header("X-Cart-Id", anonymousId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 1, "productName": "Item A", "price": 10.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String mergeBody = "{\"anonymousCartId\": \"" + anonymousId + "\"}";

        // First merge
        var firstMerge = restClient.post()
                .uri("/api/v1/cart/merge")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mergeBody)
                .retrieve()
                .toEntity(String.class);

        assertThat(firstMerge.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode firstData = jsonMapper.readTree(firstMerge.getBody()).get("data");
        assertThat(firstData.get("totalItems").intValue()).isEqualTo(1);

        // Second merge (anon cart already deleted) — should be a graceful no-op
        var secondMerge = restClient.post()
                .uri("/api/v1/cart/merge")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mergeBody)
                .retrieve()
                .toEntity(String.class);

        assertThat(secondMerge.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode secondData = jsonMapper.readTree(secondMerge.getBody()).get("data");
        assertThat(secondData.get("totalItems").intValue()).isEqualTo(1);
    }

    @Test
    void shouldResetTtlOnCartAccess() throws InterruptedException {
        // TTL is 1 minute. Access at 40s resets TTL. Cart should survive past 80s total.
        var createResponse = restClient.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": 88, "productName": "Refreshing Item", "price": 12.00, "quantity": 1}
                        """)
                .retrieve()
                .toEntity(String.class);

        String cartId = createResponse.getHeaders().getFirst("X-Cart-Id");

        // Wait 40 seconds, then access cart to reset TTL
        Thread.sleep(40_000);

        var refreshResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Wait another 40 seconds (total 80s from creation, 40s from last access)
        // Cart should still exist because TTL was reset at ~40s mark
        Thread.sleep(40_000);

        var stillAliveResponse = restClient.get()
                .uri("/api/v1/cart")
                .header("X-Cart-Id", cartId)
                .retrieve()
                .toEntity(String.class);
        assertThat(stillAliveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
