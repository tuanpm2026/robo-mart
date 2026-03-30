package com.robomart.gateway.config;

import com.robomart.security.converter.KeycloakRealmRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    private final KeycloakRealmRoleConverter keycloakRealmRoleConverter;

    public GatewaySecurityConfig(KeycloakRealmRoleConverter keycloakRealmRoleConverter) {
        this.keycloakRealmRoleConverter = keycloakRealmRoleConverter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .authorizeExchange(exchanges -> exchanges
                        // Public endpoints — no auth required
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/api/v1/products/**").permitAll()
                        .pathMatchers("/api/v1/cart/**").permitAll()
                        .pathMatchers("/graphql").permitAll()
                        // All other routes — permit for now, Story 3.3 adds RBAC.
                        // Note: permitAll() still rejects requests with invalid Bearer tokens.
                        // This is intentional Spring Security behavior.
                        .anyExchange().permitAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(reactiveJwtConverter())
                        )
                )
                .csrf(ServerHttpSecurity.CsrfSpec::disable);

        return http.build();
    }

    private ReactiveJwtAuthenticationConverterAdapter reactiveJwtConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(keycloakRealmRoleConverter);
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }
}
