package com.robomart.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Actual Boot decoder and HTTP security chain, without a mock JwtDecoder or remote issuer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid/realms/robomart",
        "spring.security.oauth2.resourceserver.jwt.audiences=robomart-api",
        "management.health.redis.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState"
})
@Import(GatewayJwtValidationTest.Probes.class)
class GatewayJwtValidationTest {
    private static final String ISSUER = "https://issuer.invalid/realms/robomart";
    private static final AtomicInteger JWK_REQUESTS = new AtomicInteger();
    private static final RSAKey KEY = key();
    private static final HttpServer JWKS = jwks();
    @LocalServerPort private int port;

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("platform-key").generate();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static HttpServer jwks() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", request -> {
                JWK_REQUESTS.incrementAndGet();
                request.getResponseHeaders().add("Content-Type", "application/json");
                request.sendResponseHeaders(200, keys.length);
                request.getResponseBody().write(keys);
                request.close();
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void keySource(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopKeySource() {
        JWKS.stop(0);
    }

    @Test
    void validatesSignatureIssuerAudienceAndExpiryWithLazyKeyFetching() throws Exception {
        var http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        assertThat(JWK_REQUESTS.get()).as("Explicit JWK URI avoids issuer discovery/key fetching at startup").isZero();
        http.get().uri("/api/v1/orders/jwt-probe").exchange().expectStatus().isUnauthorized();
        assertThat(JWK_REQUESTS.get()).isZero();
        String valid = token(ISSUER, "robomart-api", 300, KEY);
        http.get().uri("/api/v1/orders/jwt-probe")
                .headers(headers -> headers.setBearerAuth(valid))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("accepted");
        assertThat(JWK_REQUESTS.get()).isPositive();

        for (String invalid : List.of(
                token("https://other.invalid", "robomart-api", 300, KEY),
                token(null, "robomart-api", 300, KEY),
                token(ISSUER, "other-api", 300, KEY),
                token(ISSUER, null, 300, KEY),
                token(ISSUER, "robomart-api", -120, KEY),
                token(ISSUER, "robomart-api", 300, key()))) {
            http.get().uri("/api/v1/orders/jwt-probe").headers(headers -> headers.setBearerAuth(invalid))
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    private static String token(String issuer, String audience, int expirySeconds, RSAKey signer) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("platform-customer").issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(180)))
                .expirationTime(Date.from(Instant.now().plusSeconds(expirySeconds)))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("platform-key").build(), claims);
        token.sign(new RSASSASigner(signer));
        return token.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean
        Probe jwtProbe() {
            return new Probe();
        }
    }

    @TestComponent
    @RestController
    static class Probe {
        @GetMapping("/api/v1/orders/jwt-probe")
        public String accepted() {
            return "accepted";
        }
    }
}
