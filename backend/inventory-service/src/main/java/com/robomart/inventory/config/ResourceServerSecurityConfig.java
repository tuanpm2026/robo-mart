package com.robomart.inventory.config;

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
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
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
