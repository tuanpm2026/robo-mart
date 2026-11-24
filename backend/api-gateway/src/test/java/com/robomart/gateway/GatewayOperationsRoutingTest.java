package com.robomart.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

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
import reactor.netty.DisposableServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
        "management.health.redis.enabled=false",
        "gateway.cors.admin-origin=https://admin.example.test",
        "management.endpoint.health.group.readiness.include=readinessState"})
class GatewayOperationsRoutingTest {
    private static final HttpServer PRODUCT = server("product");
    private static final DisposableServer NOTIFICATION = reactor.netty.http.server.HttpServer.create()
            .host("127.0.0.1")
            .route(routes -> routes
                    .ws("/ws/server/session/websocket", (in, out) -> out.sendString(in.receive().asString()))
                    .route(request -> true, (request, response) ->
                            response.sendString(Mono.just("notification:" + request.uri()))))
            .bindNow();
    @MockitoBean private ReactiveJwtDecoder decoder;
    @MockitoBean(name = "authenticatedRateLimiter") private RedisRateLimiter rateLimiter;
    @MockitoBean(name = "anonymousRateLimiter") private RedisRateLimiter anonymousRateLimiter;
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
        properties.add("gateway.services.notification-service", () -> "http://127.0.0.1:" + NOTIFICATION.port());
    }

    @BeforeEach
    void setup() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(
                Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "99"))));
        when(anonymousRateLimiter.isAllowed(anyString(), anyString())).thenReturn(
                Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "99"))));
        when(decoder.decode("admin")).thenReturn(Mono.just(Jwt.withTokenValue("admin").header("alg", "none")
                .subject("operations-admin").claim("realm_access", Map.of("roles", List.of("ADMIN"))).build()));
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void stop() {
        PRODUCT.stop(0);
        NOTIFICATION.disposeNow();
    }

    @Test
    void routesCategoriesAndProductAuditWithoutShadowingGlobalAuditAggregation() {
        expect("/api/v1/admin/categories", "product:/api/v1/admin/categories");
        expect("/api/v1/admin/categories/1", "product:/api/v1/admin/categories/1");
        expect("/api/v1/admin/products/audit-logs?size=100", "product:/api/v1/admin/audit-logs?size=100");
        expect("/api/v1/admin/audit-logs?size=20", "notification:/api/v1/admin/audit-logs?size=20");
        expect("/api/v1/admin/reconciliation/status", "notification:/api/v1/admin/reconciliation/status");
        client.get().uri("/images/1/test.jpg").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("product:/images/1/test.jpg");
        client.put().uri("/images/1/test.jpg").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/admin/categories").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void routesWebSocketUpgradeFromConfiguredAdminOrigin() {
        URI uri = URI.create("ws://localhost:" + port + "/ws/server/session/websocket");
        try (var http = HttpClient.newHttpClient()) {
            WebSocket socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .header("Origin", "https://admin.example.test")
                    .buildAsync(uri, new WebSocket.Listener() { })
                    .orTimeout(5, TimeUnit.SECONDS).join();
            socket.abort();

            CompletionException rejected = assertThrows(CompletionException.class,
                    () -> http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                            .header("Origin", "https://untrusted.example.test")
                            .buildAsync(uri, new WebSocket.Listener() { })
                            .orTimeout(5, TimeUnit.SECONDS).join());
            WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class,
                    rejected.getCause());
            assertEquals(403, handshake.getResponse().statusCode());
        }
    }

    private void expect(String path, String body) {
        client.get().uri(path).headers(headers -> headers.setBearerAuth("admin"))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo(body);
    }
}
