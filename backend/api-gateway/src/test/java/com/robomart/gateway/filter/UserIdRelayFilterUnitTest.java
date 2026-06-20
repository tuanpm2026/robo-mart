package com.robomart.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Pure unit test that drives {@link UserIdRelayFilter#filter} directly with a mocked
 * {@link GatewayFilterChain} and an in-line {@link ReactiveSecurityContextHolder} context, so every
 * branch of the reactive header-relay logic runs deterministically (the {@code @SpringBootTest}
 * variant short-circuits before these operators execute, leaving them uncovered).
 */
@DisplayName("UserIdRelayFilter (unit)")
class UserIdRelayFilterUnitTest {

    private static final String X_USER_ID = "X-User-Id";

    private UserIdRelayFilter filter;
    private GatewayFilterChain chain;
    private ArgumentCaptor<ServerWebExchange> exchangeCaptor;

    @BeforeEach
    void setUp() {
        filter = new UserIdRelayFilter();
        chain = mock(GatewayFilterChain.class);
        exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(exchangeCaptor.capture())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchangeWithHeaders(MockServerHttpRequest.BaseBuilder<?> builder) {
        return MockServerWebExchange.from(builder.build());
    }

    private Authentication jwtAuthentication(Jwt jwt) {
        return new UsernamePasswordAuthenticationToken(jwt, "n/a");
    }

    private Mono<Void> runWithAuth(ServerWebExchange exchange, Authentication authentication) {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        return filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
    }

    private String relayedUserId() {
        return exchangeCaptor.getValue().getRequest().getHeaders().getFirst(X_USER_ID);
    }

    @Test
    @DisplayName("overwrites X-User-Id with the JWT subject when authenticated")
    void overwritesUserIdWithJwtSubject() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject("real-user-99").build();
        MockServerWebExchange exchange = exchangeWithHeaders(
                MockServerHttpRequest.get("/api/v1/cart/1").header(X_USER_ID, "spoofed"));

        StepVerifier.create(runWithAuth(exchange, jwtAuthentication(jwt))).verifyComplete();

        assertThat(relayedUserId()).isEqualTo("real-user-99");
    }

    @Test
    @DisplayName("leaves request untouched when authenticated JWT has no subject")
    void leavesRequestUntouchedWhenJwtSubjectNull() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("scope", "read").build();
        MockServerWebExchange exchange = exchangeWithHeaders(
                MockServerHttpRequest.get("/api/v1/cart/1").header(X_USER_ID, "client-supplied"));

        StepVerifier.create(runWithAuth(exchange, jwtAuthentication(jwt))).verifyComplete();

        // sub == null -> withAuthenticatedUserId returns the exchange unchanged
        assertThat(relayedUserId()).isEqualTo("client-supplied");
    }

    @Test
    @DisplayName("falls through to anonymous handling when principal is not a JWT")
    void fallsThroughWhenPrincipalNotJwt() {
        Authentication nonJwt = new UsernamePasswordAuthenticationToken("plain-user", "n/a");
        MockServerWebExchange exchange = exchangeWithHeaders(
                MockServerHttpRequest.get("/api/v1/cart/1")
                        .header(X_USER_ID, "550e8400-e29b-41d4-a716-446655440000"));

        StepVerifier.create(runWithAuth(exchange, nonJwt)).verifyComplete();

        // Not a Jwt -> filtered out -> anonymous path keeps the valid header
        assertThat(relayedUserId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("passes through a valid anonymous X-User-Id when unauthenticated")
    void passesThroughValidAnonymousUserId() {
        MockServerWebExchange exchange = exchangeWithHeaders(
                MockServerHttpRequest.get("/api/v1/cart/1").header(X_USER_ID, "anon-valid-id"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(relayedUserId()).isEqualTo("anon-valid-id");
    }

    @Test
    @DisplayName("leaves request untouched when no X-User-Id header is present")
    void leavesRequestUntouchedWhenNoHeader() {
        MockServerWebExchange exchange = exchangeWithHeaders(
                MockServerHttpRequest.get("/api/v1/cart/1"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(relayedUserId()).isNull();
    }

    @Test
    @DisplayName("strips an oversized anonymous X-User-Id")
    void stripsOversizedAnonymousUserId() {
        MockServerWebExchange exchange = exchangeWithHeaders(
                MockServerHttpRequest.get("/api/v1/cart/1").header(X_USER_ID, "a".repeat(129)));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(relayedUserId()).isNull();
    }

    @Test
    @DisplayName("getOrder runs just before the lowest precedence")
    void orderIsJustBeforeLowestPrecedence() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MAX_VALUE - 1);
    }
}
