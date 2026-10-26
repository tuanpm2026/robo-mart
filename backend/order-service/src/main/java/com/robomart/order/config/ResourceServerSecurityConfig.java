package com.robomart.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.robomart.security.converter.KeycloakRealmRoleConverter;

/**
 * Defense-in-depth resource-server security (Wave 2). Validates the Keycloak JWT at the service
 * boundary: admin endpoints require ADMIN, except reading a single order also permits the
 * restricted NOTIFICATION_SERVICE identity. Other order endpoints require an authenticated user
 * without that service role, so direct in-cluster calls cannot bypass RBAC. HTTP only — the
 * gRPC server is protected by the NetworkPolicy (ingress restricted to callers, not exposed).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class ResourceServerSecurityConfig {

    private final KeycloakRealmRoleConverter keycloakRealmRoleConverter;

    public ResourceServerSecurityConfig(KeycloakRealmRoleConverter keycloakRealmRoleConverter) {
        this.keycloakRealmRoleConverter = keycloakRealmRoleConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/orders/{orderId:[0-9]+}")
                    .hasAnyRole("ADMIN", "NOTIFICATION_SERVICE")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/internal/inventory/orders/{orderId:[0-9]+}/status")
                    .hasRole("INVENTORY_SERVICE")
                .requestMatchers("/api/v1/internal/**").denyAll()
                .anyRequest().access(AuthorizationManagers.allOf(
                        AuthenticatedAuthorizationManager.authenticated(),
                        AuthorizationManagers.not(AuthorityAuthorizationManager.hasRole("NOTIFICATION_SERVICE")),
                        AuthorizationManagers.not(AuthorityAuthorizationManager.hasRole("INVENTORY_SERVICE")))))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(keycloakRealmRoleConverter);
        return converter;
    }
}
