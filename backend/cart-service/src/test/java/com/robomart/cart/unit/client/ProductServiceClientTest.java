package com.robomart.cart.unit.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.robomart.cart.client.ProductServiceClient;
import com.robomart.cart.client.ProductSnapshot;
import com.robomart.common.dto.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@code @HttpExchange} contract against product-service's real response shape
 * ({@code ApiResponse<ProductDetailResponse>}) using a throwaway local HTTP server.
 */
class ProductServiceClientTest {

    private static final String PRODUCT_JSON = """
            {"data":{"id":42,"sku":"RB-42","name":"Robot Arm","description":"d","price":199.90,
            "rating":4.5,"brand":"Robo","stockQuantity":3,"category":{"id":1,"name":"Arms"},
            "images":[],"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},
            "traceId":"abc"}
            """;

    private HttpServer server;
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private ProductServiceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ProductServiceClient.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestedPath.set(exchange.getRequestURI().getPath());
        boolean found = "/api/v1/products/42".equals(exchange.getRequestURI().getPath());
        byte[] body = (found ? PRODUCT_JSON : "{\"error\":{\"code\":\"PRODUCT_NOT_FOUND\"}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(found ? 200 : 404, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void shouldFetchProductDetailAndIgnoreUnknownFields() {
        ApiResponse<ProductSnapshot> response = client.getProduct(42L);

        assertThat(requestedPath.get()).isEqualTo("/api/v1/products/42");
        assertThat(response.data().id()).isEqualTo(42L);
        assertThat(response.data().name()).isEqualTo("Robot Arm");
        assertThat(response.data().price()).isEqualByComparingTo("199.90");
    }

    @Test
    void shouldSurfaceNotFoundAsHttpClientError() {
        assertThatThrownBy(() -> client.getProduct(7L))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
