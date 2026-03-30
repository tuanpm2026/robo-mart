package com.robomart.security.converter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void shouldExtractRolesWhenRealmAccessContainsRoles() {
        Jwt jwt = buildJwt(Map.of("roles", List.of("CUSTOMER", "ADMIN")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_ADMIN");
    }

    @Test
    void shouldPrefixWithRoleAndUppercaseWhenRolesAreLowercase() {
        Jwt jwt = buildJwt(Map.of("roles", List.of("customer")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void shouldReturnEmptyWhenRealmAccessClaimMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenRealmAccessHasNoRolesKey() {
        Jwt jwt = buildJwt(Map.of("other", "value"));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenRolesListIsEmpty() {
        Jwt jwt = buildJwt(Map.of("roles", List.of()));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void shouldIgnoreNonStringRoleEntries() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER", 123)))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void shouldHandleSingleRole() {
        Jwt jwt = buildJwt(Map.of("roles", List.of("CUSTOMER")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
    }

    private Jwt buildJwt(Map<String, Object> realmAccess) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .claim("realm_access", realmAccess)
                .build();
    }
}
