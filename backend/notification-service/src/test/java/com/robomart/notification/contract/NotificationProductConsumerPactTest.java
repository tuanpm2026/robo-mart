package com.robomart.notification.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;

/**
 * Pact consumer test: Notification Service → Product Service REST API.
 * Defines the contract for ProductServiceClient.getProductName().
 *
 * Generated pact files are written to ${pact.rootDir} = target/pacts/.
 * Product 10 ("Smart Fitness Watch") is pre-seeded in the test database.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "product-service", pactVersion = PactSpecVersion.V3)
class NotificationProductConsumerPactTest {

    private static final String CONTENT_TYPE_JSON = "application/json";

    @Pact(consumer = "notification-service", provider = "product-service")
    RequestResponsePact getProductDetailWhenProductExists(PactDslWithProvider builder) {
        return builder
            .given("product 10 exists")
            .uponReceiving("a GET request for product 10 detail")
                .path("/api/v1/products/10")
                .method("GET")
            .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", CONTENT_TYPE_JSON))
                .body("""
                    {
                      "data": {
                        "id": 10,
                        "sku": "ELEC-010",
                        "name": "Smart Fitness Watch",
                        "price": 179.99
                      },
                      "traceId": "pact-product-trace-001"
                    }
                    """)
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getProductDetailWhenProductExists")
    void shouldFetchProductDetailSuccessfully(MockServer mockServer) {
        RestClient client = RestClient.builder()
            .baseUrl(mockServer.getUrl())
            .build();

        String response = client.get()
            .uri("/api/v1/products/10")
            .retrieve()
            .body(String.class);

        assertThat(response).isNotNull();
        assertThat(response).contains("\"name\"");
        assertThat(response).contains("Smart Fitness Watch");
        assertThat(response).contains("\"traceId\"");
    }
}
