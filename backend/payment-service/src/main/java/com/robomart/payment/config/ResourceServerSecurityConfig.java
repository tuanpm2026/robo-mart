package com.robomart.payment.config;

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
 * Defense-in-depth resource-server security (Wave 2). The API Gateway already validates the
 * Keycloak JWT and enforces RBAC, but services must not blindly trust it: a direct in-cluster call
 * (with a forged X-User-Id header) could otherwise invoke ADMIN operations. This filter chain
 * validates the JWT at the service boundary and gates {@code /api/v1/admin/**} on the ADMIN role.
 *
 * <p>NOTE: this protects HTTP endpoints only. gRPC runs on a separate server and is not covered
 * here — it is protected by the NetworkPolicy (ingress restricted to the order-service pod).
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
                // Actuator (health/info/metrics/prometheus) is network-restricted to the monitoring
                // namespace by the NetworkPolicy; permit it so Prometheus scraping and probes work.
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // Keycloak puts realm roles in realm_access.roles (nested) and they need the ROLE_ prefix
        // for hasRole(...) — handled by the shared converter in security-lib.
        converter.setJwtGrantedAuthoritiesConverter(keycloakRealmRoleConverter);
        return converter;
    }
}
