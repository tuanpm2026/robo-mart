package com.robomart.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full E2E test: product search → add to cart → login → checkout → place order → confirm.
 *
 * <p>Requires full stack running: docker-compose --profile core --profile app up -d
 *
 * <p>Run via: ./mvnw verify -pl :e2e-tests -DskipE2ETests=false -De2e.enabled=true
 * or set system property: e2e.base-url=http://localhost:8080
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "e2e.enabled", matches = "true")
class FullOrderFlowE2EIT {

    private static final String BASE_URL = System.getProperty("e2e.base-url", "http://localhost:8080");
    private static final String KEYCLOAK_URL = System.getProperty("e2e.keycloak-url", "http://localhost:8180");
    // Admin token for admin-only endpoints (e.g. notification check); set via -De2e.admin-token=...
    private static final String ADMIN_TOKEN = System.getProperty("e2e.admin-token", "");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private RestClient apiGateway;
    private String customerToken;
    private String cartId;
    private Long productId;

    @BeforeAll
    void setUp() {
        apiGateway = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @Test
    void shouldCompleteFullOrderFlow() throws Exception {
        // Step 1: Search for products
        step1SearchProducts();

        // Step 2: Add to cart (anonymous)
        step2AddToCart();

        // Step 3: Authenticate as customer
        step3AuthenticateAsCustomer();

        // Step 4: Place order
        String orderId = step4PlaceOrder();

        // Step 5: Verify order confirmed (saga completion < 10s)
        step5VerifyOrderConfirmed(orderId);

        // Step 6: Verify notification sent (async — wait up to 30s)
        step6VerifyNotificationSent(orderId);
    }

    private void step1SearchProducts() throws Exception {
        String response = apiGateway.get()
                .uri("/api/v1/products/search?q=robot&size=1")
                .retrieve()
                .body(String.class);

        assertThat(response).isNotNull();
        assertThat(response).contains("\"data\"");

        JsonNode root = jsonMapper.readTree(response);
        JsonNode items = root.path("data").path("items");
        if (items.isArray() && items.size() > 0) {
            productId = items.get(0).path("id").asLong();
        } else {
            // Fallback to product ID 1 if search returns no results
            productId = 1L;
        }
        assertThat(productId).isPositive();
    }

    private void step2AddToCart() throws Exception {
        ResponseEntity<String> cartResponse = apiGateway.post()
                .uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": %d, "productName": "Robot Product", "price": 29.99, "quantity": 1}
                        """.formatted(productId))
                .retrieve()
                .toEntity(String.class);

        assertThat(cartResponse.getStatusCode().value()).isIn(200, 201);
        // Cart ID returned via X-Cart-Id response header
        cartId = cartResponse.getHeaders().getFirst("X-Cart-Id");
        assertThat(cartId).isNotNull().isNotBlank();

        String body = cartResponse.getBody();
        assertThat(body).contains("\"cartId\"");
    }

    private void step3AuthenticateAsCustomer() throws Exception {
        RestClient keycloakClient = RestClient.builder()
                .baseUrl(KEYCLOAK_URL)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();

        String tokenResponse = keycloakClient.post()
                .uri("/realms/robomart/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // client_id must match realm config: robo-mart-frontend (with hyphen)
                .body("grant_type=password&client_id=robo-mart-frontend"
                        + "&username=testcustomer@robomart.com"
                        + "&password=testpassword123")
                .retrieve()
                .body(String.class);

        assertThat(tokenResponse).contains("access_token");

        JsonNode root = jsonMapper.readTree(tokenResponse);
        customerToken = root.path("access_token").asText();
        assertThat(customerToken).isNotBlank();
    }

    private String step4PlaceOrder() throws Exception {
        String orderResponse = apiGateway.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + customerToken)
                .body("""
                        {
                          "items": [{"productId": %d, "quantity": 1}],
                          "shippingAddress": "1 Test St, Austin, TX, 75001, US"
                        }
                        """.formatted(productId))
                .retrieve()
                .body(String.class);

        assertThat(orderResponse).isNotNull();
        assertThat(orderResponse).contains("\"id\"");

        JsonNode root = jsonMapper.readTree(orderResponse);
        String orderId = root.path("data").path("id").asText();
        assertThat(orderId).isNotBlank();
        return orderId;
    }

    private void step5VerifyOrderConfirmed(String orderId) {
        await().atMost(10, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            String statusResponse = apiGateway.get()
                    .uri("/api/v1/orders/" + orderId)
                    .header("Authorization", "Bearer " + customerToken)
                    .retrieve()
                    .body(String.class);

            assertThat(statusResponse).contains("CONFIRMED");
        });
    }

    private void step6VerifyNotificationSent(String orderId) {
        // Notification is async (Kafka consumer). Wait up to 30s.
        // Admin endpoint requires ROLE_ADMIN token — use e2e.admin-token system property.
        assertThat(ADMIN_TOKEN).as("e2e.admin-token must be set to verify notifications").isNotBlank();

        await().atMost(30, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String notifResponse = apiGateway.get()
                            .uri("/api/v1/admin/notifications?orderId=" + orderId)
                            .header("Authorization", "Bearer " + ADMIN_TOKEN)
                            .retrieve()
                            .body(String.class);

                    assertThat(notifResponse).isNotNull();
                    JsonNode root = jsonMapper.readTree(notifResponse);
                    JsonNode notifications = root.path("data");
                    assertThat(notifications.isArray() && notifications.size() > 0)
                            .as("Expected at least one notification for orderId=%s", orderId)
                            .isTrue();
                });
    }
}
