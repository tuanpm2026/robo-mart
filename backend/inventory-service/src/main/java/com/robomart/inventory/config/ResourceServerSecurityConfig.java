package com.robomart.inventory.config;

import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.robomart.security.converter.KeycloakRealmRoleConverter;

/**
 * Defense-in-depth resource-server security (Wave 2). Validates the Keycloak JWT at the service
 * boundary and gates {@code /api/v1/admin/**} on the ADMIN role, so a direct in-cluster call cannot
 * bypass the gateway's RBAC. HTTP only — gRPC runs on a separate server protected by the
 * NetworkPolicy (ingress restricted to the order-service pod).
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
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/inventory/reconciliation-summary", "/api/v1/admin/inventory/audit-logs")
                    .hasAnyRole("ADMIN", "NOTIFICATION_SERVICE")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().access(AuthorizationManagers.allOf(
                        AuthenticatedAuthorizationManager.authenticated(),
                        AuthorizationManagers.not(AuthorityAuthorizationManager.hasRole("NOTIFICATION_SERVICE")))))
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
