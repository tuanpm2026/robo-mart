package com.robomart.gateway;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
        "management.health.redis.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState"})
class GatewayOperationsRoutingTest {
    private static final HttpServer PRODUCT = server("product");
    private static final HttpServer NOTIFICATION = server("notification");
    @MockitoBean private ReactiveJwtDecoder decoder;
    @MockitoBean(name = "authenticatedRateLimiter") private RedisRateLimiter rateLimiter;
    @LocalServerPort private int port;
    private WebTestClient client;

    private static HttpServer server(String name) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", request -> {
                byte[] body = (name + ":" + request.getRequestURI()).getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
                request.close();
            });
            server.start();
            return server;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry properties) {
        properties.add("gateway.services.product-service", () -> "http://127.0.0.1:" + PRODUCT.getAddress().getPort());
        properties.add("gateway.services.notification-service", () -> "http://127.0.0.1:" + NOTIFICATION.getAddress().getPort());
    }

    @BeforeEach
    void setup() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(
                Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "99"))));
        when(decoder.decode("admin")).thenReturn(Mono.just(Jwt.withTokenValue("admin").header("alg", "none")
                .subject("operations-admin").claim("realm_access", Map.of("roles", List.of("ADMIN"))).build()));
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void stop() { PRODUCT.stop(0); NOTIFICATION.stop(0); }

    @Test
    void routesCategoriesAndProductAuditWithoutShadowingGlobalAuditAggregation() {
        expect("/api/v1/admin/categories", "product:/api/v1/admin/categories");
        expect("/api/v1/admin/categories/1", "product:/api/v1/admin/categories/1");
        expect("/api/v1/admin/products/audit-logs?size=100", "product:/api/v1/admin/audit-logs?size=100");
        expect("/api/v1/admin/audit-logs?size=20", "notification:/api/v1/admin/audit-logs?size=20");
        expect("/api/v1/admin/reconciliation/status", "notification:/api/v1/admin/reconciliation/status");
        client.get().uri("/api/v1/admin/categories").exchange().expectStatus().isUnauthorized();
    }

    private void expect(String path, String body) {
        client.get().uri(path).headers(headers -> headers.setBearerAuth("admin"))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo(body);
    }
}
