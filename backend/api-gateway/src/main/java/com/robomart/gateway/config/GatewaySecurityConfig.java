package com.robomart.gateway.config;

import com.robomart.security.converter.KeycloakRealmRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/api/v1/products/**").permitAll()
                        .pathMatchers("/graphql").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/cart/merge").authenticated()
                        .pathMatchers("/api/v1/cart/**").permitAll()
                        .pathMatchers("/api/v1/orders/**").authenticated()
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated()
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
