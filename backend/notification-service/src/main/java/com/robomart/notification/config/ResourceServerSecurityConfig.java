package com.robomart.notification.config;

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
 * Defense-in-depth resource-server security (Wave 2) for notification-service's HTTP endpoints.
 * Its admin controllers (DLQ replay, reconciliation, audit, system-health) were previously only
 * guarded by the gateway; this gates {@code /api/v1/admin/**} on the ADMIN role at the service.
 *
 * <p>The WebSocket handshake ({@code /ws/**}) is permitted here — STOMP CONNECT frames are
 * authenticated separately by {@link JwtStompInterceptor}. The {@link org.springframework.security
 * .oauth2.jwt.JwtDecoder} bean defined in {@link WebSocketConfig} is reused for HTTP token
 * validation (the resource-server auto-config backs off since that bean exists).
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
                // WebSocket/SockJS handshake — STOMP CONNECT is authenticated by JwtStompInterceptor.
                .requestMatchers("/ws/**").permitAll()
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
