package com.robomart.security.util;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextTest {

    @Test
    void shouldExtractUserIdWhenJwtAuthentication() {
        Jwt jwt = buildJwt("user-uuid-123");
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        assertThat(AuthContext.getUserId(auth)).isEqualTo("user-uuid-123");
    }

    @Test
    void shouldReturnNullUserIdWhenAuthenticationIsNull() {
        assertThat(AuthContext.getUserId(null)).isNull();
    }

    @Test
    void shouldExtractEmailWhenPresent() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .claim("email", "demo@robomart.com")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        assertThat(AuthContext.getEmail(auth)).isEqualTo("demo@robomart.com");
    }

    @Test
    void shouldReturnNullEmailWhenNotPresent() {
        Jwt jwt = buildJwt("user-123");
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        assertThat(AuthContext.getEmail(auth)).isNull();
    }

    @Test
    void shouldExtractRolesFromAuthorities() {
        Jwt jwt = buildJwt("user-123");
        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_CUSTOMER"),
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, authorities);

        assertThat(AuthContext.getRoles(auth))
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_ADMIN");
    }

    @Test
    void shouldReturnEmptyRolesWhenAuthenticationIsNull() {
        assertThat(AuthContext.getRoles(null)).isEmpty();
    }

    @Test
    void shouldReturnTrueWhenHasRole() {
        Jwt jwt = buildJwt("user-123");
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, authorities);

        assertThat(AuthContext.hasRole(auth, "ROLE_CUSTOMER")).isTrue();
        assertThat(AuthContext.hasRole(auth, "ROLE_ADMIN")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenHasRoleWithNullAuth() {
        assertThat(AuthContext.hasRole(null, "ROLE_CUSTOMER")).isFalse();
    }

    private Jwt buildJwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .build();
    }
}
