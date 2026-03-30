package com.robomart.security.converter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Extracts realm-level roles from Keycloak JWT tokens.
 * <p>
 * Keycloak stores roles in a nested {@code realm_access.roles} claim:
 * <pre>
 * { "realm_access": { "roles": ["CUSTOMER", "ADMIN"] } }
 * </pre>
 * This converter maps them to Spring Security {@code ROLE_} prefixed authorities.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Logger log = LoggerFactory.getLogger(KeycloakRealmRoleConverter.class);

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !realmAccess.containsKey(ROLES_KEY)) {
            return Collections.emptyList();
        }

        Object rolesObj = realmAccess.get(ROLES_KEY);
        if (!(rolesObj instanceof Collection<?> roles)) {
            return Collections.emptyList();
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Object role : roles) {
            if (role instanceof String roleName) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + roleName.toUpperCase()));
            } else {
                log.warn("Unexpected non-string role element in realm_access.roles: {}", role);
            }
        }
        return Collections.unmodifiableList(authorities);
    }
}
