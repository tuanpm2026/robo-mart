package com.robomart.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.health.redis.enabled=false",
        "management.server.port=0",
        "management.endpoint.health.group.readiness.include=readinessState",
        "resilience4j.circuitbreaker.instances.product-service.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.product-service.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.product-service.failure-rate-threshold=100",
        "resilience4j.circuitbreaker.instances.product-service.wait-duration-in-open-state=1h",
        "resilience4j.circuitbreaker.instances.product-service.permitted-number-of-calls-in-half-open-state=1"
})
class GatewayCircuitBreakerTest {
    private static final AtomicBoolean AVAILABLE = new AtomicBoolean();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicInteger PRODUCT_CALLS = new AtomicInteger();
    private static final HttpServer UPSTREAM = upstream();
    @MockitoBean private ReactiveJwtDecoder decoder;
    @MockitoBean(name = "authenticatedRateLimiter") private RedisRateLimiter authenticated;
    @MockitoBean(name = "anonymousRateLimiter") private RedisRateLimiter anonymous;
    @Autowired private CircuitBreakerRegistry breakers;
    @LocalServerPort private int port;
    private WebTestClient http;

    private static HttpServer upstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", request -> {
                if (request.getRequestURI().getPath().startsWith("/api/v1/orders/")) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    PRODUCT_CALLS.incrementAndGet();
                    if (!AVAILABLE.get()) {
                        request.close();
                        return;
                    }
                }
                byte[] body = (STATUS.get() == 502 ? "{\"status\":\"ERROR\"}" : "healthy")
                        .getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(STATUS.get(), body.length);
                request.getResponseBody().write(body);
                request.close();
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry properties) {
        properties.add("gateway.services.product-service", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        properties.add("gateway.services.order-service", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @BeforeEach
    void setUp() {
        breakers.circuitBreaker("product-service").reset();
        breakers.circuitBreaker("order-service").reset();
        AVAILABLE.set(true);
        STATUS.set(200);
        PRODUCT_CALLS.set(0);
        var allowed = Mono.just(new RateLimiter.Response(true, Map.of()));
        when(authenticated.isAllowed(anyString(), anyString())).thenReturn(allowed);
        when(anonymous.isAllowed(anyString(), anyString())).thenReturn(allowed);
        when(decoder.decode("customer")).thenReturn(Mono.just(Jwt.withTokenValue("customer").header("alg", "none")
                .subject("circuit-customer").claim("realm_access", Map.of("roles", List.of("CUSTOMER"))).build()));
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    @Test
    void transportFailuresOpenTheCircuitAndRecoveryReachesTheSameBackend() {
        AVAILABLE.set(false);
        for (int i = 0; i < 2; i++) {
            http.get().uri("/api/v1/products/circuit-probe").exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE")
                    .jsonPath("$.status").isEqualTo(503);
        }
        var breaker = breakers.circuitBreaker("product-service");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int attempted = PRODUCT_CALLS.get();
        http.get().uri("/api/v1/products/circuit-probe").exchange().expectStatus().isEqualTo(503);
        assertThat(PRODUCT_CALLS.get()).as("OPEN does not invoke the backend").isEqualTo(attempted);
        AVAILABLE.set(true);
        breaker.transitionToHalfOpenState();
        http.get().uri("/api/v1/products/circuit-probe").exchange().expectStatus().isOk();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED));
    }

    @Test
    void preservesApplicationErrorStatusAndBody() {
        STATUS.set(502);
        http.get().uri("/api/v1/products/circuit-probe").exchange().expectStatus().isEqualTo(502)
                .expectBody(String.class).isEqualTo("{\"status\":\"ERROR\"}");
        assertThat(breakers.circuitBreaker("product-service").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void checkoutDoesNotInheritTheCircuitLibraryOneSecondTimeLimit() {
        http.get().uri("/api/v1/orders/slow-probe").headers(headers -> headers.setBearerAuth("customer"))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("healthy");
    }
}
