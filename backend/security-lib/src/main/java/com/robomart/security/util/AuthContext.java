package com.robomart.security.util;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Utility for extracting user context from Spring Security {@link Authentication}.
 * <p>
 * Framework-agnostic — works with both servlet ({@code SecurityContextHolder})
 * and reactive ({@code ReactiveSecurityContextHolder}) by accepting {@link Authentication}
 * as a parameter.
 */
public final class AuthContext {

    private AuthContext() {
    }

    /**
     * Extracts the user ID ({@code sub} claim) from a JWT-based authentication.
     *
     * @param authentication the current authentication, may be null
     * @return the user ID, or null if not a JWT authentication or the JWT lacks a 'sub' claim
     */
    public static String getUserId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return jwt.getSubject();
        }
        return null;
    }

    /**
     * Extracts the user's email from the JWT {@code email} claim.
     *
     * @param authentication the current authentication, may be null
     * @return the email or null if not available
     */
    public static String getEmail(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getClaimAsString("email");
        }
        return null;
    }

    /**
     * Returns all granted authority strings from the authentication.
     *
     * @param authentication the current authentication, may be null
     * @return set of authority strings (e.g., "ROLE_CUSTOMER"), empty if null
     */
    public static Set<String> getRoles(Authentication authentication) {
        if (authentication == null) {
            return Collections.emptySet();
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities == null) {
            return Collections.emptySet();
        }
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Checks if the authentication has a specific role.
     *
     * @param authentication the current authentication, may be null
     * @param role           the role to check (e.g., "ROLE_CUSTOMER")
     * @return true if the role is present
     */
    public static boolean hasRole(Authentication authentication, String role) {
        return getRoles(authentication).contains(role);
    }
}
