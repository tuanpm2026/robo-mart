package com.robomart.gateway;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.BadJwtException;
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
class GatewaySecurityRbacTest {

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    private static final String CUSTOMER_TOKEN = "customer-token";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String CUSTOMER_USER_ID = "customer-uuid-123";
    private static final String ADMIN_USER_ID = "admin-uuid-456";

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        Jwt customerJwt = Jwt.withTokenValue(CUSTOMER_TOKEN)
                .header("alg", "RS256")
                .claim("sub", CUSTOMER_USER_ID)
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .build();
        when(jwtDecoder.decode(CUSTOMER_TOKEN)).thenReturn(Mono.just(customerJwt));

        Jwt adminJwt = Jwt.withTokenValue(ADMIN_TOKEN)
                .header("alg", "RS256")
                .claim("sub", ADMIN_USER_ID)
                .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                .build();
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(Mono.just(adminJwt));
    }

    private void expectNotAuthBlocked(WebTestClient.ResponseSpec response) {
        response.expectStatus().value(status ->
                assertThat(status).isNotIn(401, 403));
    }

    @Nested
    class PublicEndpoints {

        @Test
        void shouldAllowProductsWithoutToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/products/1")
                            .exchange());
        }

        @Test
        void shouldAllowProductsWithValidToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/products/1")
                            .headers(h -> h.setBearerAuth(CUSTOMER_TOKEN))
                            .exchange());
        }

        @Test
        void shouldAllowGraphqlWithoutToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/graphql")
                            .exchange());
        }

        @Test
        void shouldAllowCartWithoutToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/cart/abc")
                            .exchange());
        }

        @Test
        void shouldAllowHealthWithoutToken() {
            webTestClient.get().uri("/actuator/health")
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    @Nested
    class ProtectedOrderEndpoints {

        @Test
        void shouldReturn401WhenNoToken() {
            webTestClient.get().uri("/api/v1/orders/1")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void shouldReturn401WhenInvalidToken() {
            when(jwtDecoder.decode("invalid-token"))
                    .thenReturn(Mono.error(new BadJwtException("Invalid token")));

            webTestClient.get().uri("/api/v1/orders/1")
                    .headers(h -> h.setBearerAuth("invalid-token"))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void shouldAllowCustomerWithValidToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/orders/1")
                            .headers(h -> h.setBearerAuth(CUSTOMER_TOKEN))
                            .exchange());
        }

        @Test
        void shouldAllowAdminWithValidToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/orders/1")
                            .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                            .exchange());
        }
    }

    @Nested
    class AdminEndpoints {

        @Test
        void shouldReturn401WhenNoToken() {
            webTestClient.get().uri("/api/v1/admin/products")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void shouldReturn403WhenCustomerToken() {
            webTestClient.get().uri("/api/v1/admin/products")
                    .headers(h -> h.setBearerAuth(CUSTOMER_TOKEN))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        void shouldAllowAdminToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/admin/products")
                            .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                            .exchange());
        }

        @Test
        void shouldReturn403ForAdminOrdersWithCustomerToken() {
            webTestClient.get().uri("/api/v1/admin/orders")
                    .headers(h -> h.setBearerAuth(CUSTOMER_TOKEN))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        void shouldAllowAdminOrdersWithAdminToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/admin/orders")
                            .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                            .exchange());
        }

        @Test
        void shouldReturn403ForAdminInventoryWithCustomerToken() {
            webTestClient.get().uri("/api/v1/admin/inventory")
                    .headers(h -> h.setBearerAuth(CUSTOMER_TOKEN))
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    @Nested
    class DenyByDefault {

        @Test
        void shouldReturn401ForUnknownPathWithoutToken() {
            webTestClient.get().uri("/api/v1/unknown")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void shouldAllowUnknownPathWithValidToken() {
            expectNotAuthBlocked(
                    webTestClient.get().uri("/api/v1/unknown")
                            .headers(h -> h.setBearerAuth(CUSTOMER_TOKEN))
                            .exchange());
        }
    }
}
