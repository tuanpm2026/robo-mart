package com.robomart.test.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test-only {@link JwtDecoder} that resolves the fixed {@link TestJwt} tokens into a Keycloak-shaped
 * JWT (roles under {@code realm_access.roles}) — so JWT-secured services' integration tests can
 * authenticate as ADMIN/CUSTOMER without a running Keycloak. Defining this bean makes the
 * resource-server auto-configuration back off its real (jwk-set-uri) decoder.
 *
 * <p>Usage: {@code @Import(TestJwtDecoderConfig.class)} on the IT and send
 * {@code TestJwt.adminBearer()} as the Authorization header. An unknown/absent token is rejected,
 * so a no-token request still yields 401 — letting tests assert that auth is enforced.
 */
@TestConfiguration
public class TestJwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            List<String> roles = switch (token) {
                case TestJwt.ADMIN_TOKEN -> List.of("ADMIN");
                case TestJwt.CUSTOMER_TOKEN -> List.of("CUSTOMER");
                default -> throw new BadJwtException("Unknown test token: " + token);
            };
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-user-" + roles.get(0).toLowerCase())
                    .claim("realm_access", Map.of("roles", roles))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .build();
        };
    }
}
