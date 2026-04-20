package com.robomart.gateway.config;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

// NOTE: 429 response behavior (AC1, AC2) is NOT tested here — verifying actual rate limit
// enforcement requires a live Redis (Testcontainers). Functional verification is done via
// Docker Compose integration. See Story 8.3 deferred items.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
            "spring.data.redis.host=localhost",
            "management.health.redis.enabled=false",
            "management.endpoint.health.group.readiness.include=readinessState"
        }
)
class RateLimitConfigTest {

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @Autowired
    KeyResolver userKeyResolver;

    @Test
    void authenticatedRequestReturnsUserKey() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .build();
        JwtAuthenticationToken authToken = new JwtAuthenticationToken(jwt, Collections.emptyList());

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("192.168.1.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(request)
                .principal(authToken)
                .build();

        StepVerifier.create(userKeyResolver.resolve(exchange))
                .assertNext(key -> assertThat(key).startsWith("user:"))
                .verifyComplete();
    }

    @Test
    void anonymousRequestReturnsIpKey() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 54321))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(userKeyResolver.resolve(exchange))
                .assertNext(key -> assertThat(key).startsWith("ip:"))
                .verifyComplete();
    }

    @Test
    void anonymousRequestWithXForwardedForUsesClientIp() {
        // Simulates a request behind a proxy: X-Forwarded-For contains real client IP
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("172.16.0.1", 0))  // proxy IP
                .header("X-Forwarded-For", "203.0.113.45")              // real client IP
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(userKeyResolver.resolve(exchange))
                .assertNext(key -> {
                    assertThat(key).startsWith("ip:");
                    assertThat(key).isEqualTo("ip:203.0.113.45");
                })
                .verifyComplete();
    }

    @Test
    void nonJwtPrincipalFallsBackToIp() {
        Principal plainPrincipal = () -> "plainUser";

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("172.16.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(request)
                .principal(plainPrincipal)
                .build();

        StepVerifier.create(userKeyResolver.resolve(exchange))
                .assertNext(key -> assertThat(key).startsWith("ip:"))
                .verifyComplete();
    }
}
