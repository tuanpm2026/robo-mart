package com.robomart.gateway;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import static org.mockito.Mockito.when;

/**
 * Fallback posture when management is co-located on the public port (management.server.port unset):
 * only health/info stay public, every other actuator endpoint requires ADMIN.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
            "management.server.port=",
            "management.health.redis.enabled=false",
            "management.endpoint.health.group.readiness.include=readinessState"
        }
)
class GatewayActuatorSamePortSecurityTest {

    private static final String CUSTOMER_TOKEN = "customer-token";
    private static final String ADMIN_TOKEN = "admin-token";

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        when(jwtDecoder.decode(CUSTOMER_TOKEN)).thenReturn(Mono.just(jwt(CUSTOMER_TOKEN, "CUSTOMER")));
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(Mono.just(jwt(ADMIN_TOKEN, "ADMIN")));
    }

    private static Jwt jwt(String token, String role) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .claim("sub", token + "-sub")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }

    @Test
    void shouldAllowHealthWithoutToken() {
        webTestClient.get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldReturn401ForPrometheusWithoutToken() {
        webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturn403ForPrometheusWithCustomerToken() {
        webTestClient.get().uri("/actuator/prometheus")
                .headers(h -> h.setBearerAuth(CUSTOMER_TOKEN))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void shouldAllowPrometheusWithAdminToken() {
        webTestClient.get().uri("/actuator/prometheus")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
    }
}
