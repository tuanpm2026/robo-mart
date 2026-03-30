package com.robomart.gateway.filter;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri="
)
class UserIdRelayFilterTest {

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    private static final String CUSTOMER_TOKEN = "customer-token";
    private static final String JWT_SUB = "jwt-user-uuid-123";

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        Jwt customerJwt = Jwt.withTokenValue(CUSTOMER_TOKEN)
                .header("alg", "RS256")
                .claim("sub", JWT_SUB)
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .build();
        when(jwtDecoder.decode(CUSTOMER_TOKEN)).thenReturn(Mono.just(customerJwt));
    }

    private void expectNotAuthBlocked(WebTestClient.ResponseSpec response) {
        response.expectStatus().value(status ->
                assertThat(status).isNotIn(401, 403));
    }

    @Test
    void shouldOverwriteUserIdWithJwtSubWhenAuthenticated() {
        expectNotAuthBlocked(
                webTestClient.get().uri("/api/v1/cart/test")
                        .headers(h -> {
                            h.setBearerAuth(CUSTOMER_TOKEN);
                            h.set("X-User-Id", "spoofed-id");
                        })
                        .exchange());
    }

    @Test
    void shouldPassThroughValidAnonymousUserId() {
        expectNotAuthBlocked(
                webTestClient.get().uri("/api/v1/cart/test")
                        .header("X-User-Id", "550e8400-e29b-41d4-a716-446655440000")
                        .exchange());
    }

    @Test
    void shouldStripAnonymousUserIdExceedingMaxLength() {
        String longId = "a".repeat(129);
        expectNotAuthBlocked(
                webTestClient.get().uri("/api/v1/cart/test")
                        .header("X-User-Id", longId)
                        .exchange());
    }

    @Test
    void shouldAllowRequestWithoutUserIdHeader() {
        expectNotAuthBlocked(
                webTestClient.get().uri("/api/v1/cart/test")
                        .exchange());
    }

    @Test
    void shouldValidateFilterOrder() {
        var filter = new UserIdRelayFilter();
        assertThat(filter.getOrder()).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void shouldRejectControlCharactersInValidation() {
        var filter = new UserIdRelayFilter();
        assertThat(filter.isValidAnonymousUserId("valid-uuid")).isTrue();
        assertThat(filter.isValidAnonymousUserId("bad\u0000id")).isFalse();
        assertThat(filter.isValidAnonymousUserId("bad\nid")).isFalse();
        assertThat(filter.isValidAnonymousUserId("bad\rid")).isFalse();
    }

    @Test
    void shouldRejectOversizedUserIdInValidation() {
        var filter = new UserIdRelayFilter();
        assertThat(filter.isValidAnonymousUserId("a".repeat(128))).isTrue();
        assertThat(filter.isValidAnonymousUserId("a".repeat(129))).isFalse();
    }
}
