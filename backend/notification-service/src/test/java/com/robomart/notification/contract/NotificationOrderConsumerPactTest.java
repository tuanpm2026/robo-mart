package com.robomart.notification.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;

/**
 * Pact consumer test: Notification Service → Order Service REST API.
 * Defines the contract for OrderServiceClient.getOrderDetail().
 *
 * Generated pact files are written to ${pact.rootDir} = target/pacts/.
 * The provider (order-service) must verify against these pact files.
 *
 * Build order: run notification-service first to generate pacts, then order-service.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "order-service", pactVersion = PactSpecVersion.V3)
class NotificationOrderConsumerPactTest {

    private static final String CONTENT_TYPE_JSON = "application/json";

    @Pact(consumer = "notification-service", provider = "order-service")
    RequestResponsePact getOrderDetailWhenOrderExists(PactDslWithProvider builder) {
        return builder
            .given("order 4 exists in CONFIRMED status")
            .uponReceiving("a GET request for order 4 admin detail")
                .path("/api/v1/admin/orders/4")
                .method("GET")
            .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", CONTENT_TYPE_JSON))
                .body("""
                    {
                      "data": {
                        "id": 4,
                        "userId": "user-002",
                        "totalAmount": 79.99,
                        "status": "CONFIRMED",
                        "shippingAddress": "{\\"street\\":\\"456 Oak Ave\\",\\"city\\":\\"Los Angeles\\",\\"state\\":\\"CA\\",\\"zip\\":\\"90001\\",\\"country\\":\\"US\\"}"
                      },
                      "traceId": "pact-trace-001"
                    }
                    """)
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getOrderDetailWhenOrderExists")
    void shouldFetchOrderDetailSuccessfully(MockServer mockServer) {
        RestClient client = RestClient.builder()
            .baseUrl(mockServer.getUrl())
            .build();

        String response = client.get()
            .uri("/api/v1/admin/orders/4")
            .retrieve()
            .body(String.class);

        assertThat(response).isNotNull();
        assertThat(response).contains("\"userId\"");
        assertThat(response).contains("\"status\"");
        assertThat(response).contains("\"CONFIRMED\"");
        assertThat(response).contains("\"traceId\"");
    }

    @Pact(consumer = "notification-service", provider = "order-service")
    RequestResponsePact getOrderDetailWhenOrderDoesNotExist(PactDslWithProvider builder) {
        return builder
            .given("order 99999 does not exist")
            .uponReceiving("a GET request for non-existent order 99999")
                .path("/api/v1/admin/orders/99999")
                .method("GET")
            .willRespondWith()
                .status(404)
                .headers(Map.of("Content-Type", CONTENT_TYPE_JSON))
                .body("""
                    {
                      "error": {
                        "code": "RESOURCE_NOT_FOUND",
                        "message": "Order not found: 99999"
                      },
                      "traceId": "pact-trace-404"
                    }
                    """)
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getOrderDetailWhenOrderDoesNotExist")
    void shouldHandleOrderNotFoundGracefully(MockServer mockServer) {
        RestClient client = RestClient.builder()
            .baseUrl(mockServer.getUrl())
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                // Don't throw — we assert the body directly
            })
            .build();

        String response = client.get()
            .uri("/api/v1/admin/orders/99999")
            .retrieve()
            .body(String.class);

        assertThat(response).isNotNull();
        assertThat(response).contains("RESOURCE_NOT_FOUND");
    }
}
