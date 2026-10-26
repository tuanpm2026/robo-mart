package com.robomart.gateway.config;

import com.robomart.security.converter.KeycloakRealmRoleConverter;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
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

    /**
     * Actuator endpoints are served on {@code management.server.port}, which is reachable only inside
     * the cluster (no LoadBalancer/Ingress exposure). With a separate management port the
     * {@link EndpointRequest} matchers only match requests that arrive on the management server, so
     * Prometheus can scrape without credentials while the public listener exposes no actuator path.
     * If management is ever co-located on the public port, only health/info stay public and every
     * other endpoint requires ADMIN.
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, Environment environment) {
        boolean separateManagementPort =
                ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;
        http
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> {
                    exchanges.matchers(EndpointRequest.to("health", "info")).permitAll();
                    if (separateManagementPort) {
                        exchanges.matchers(EndpointRequest.toAnyEndpoint()).permitAll();
                    } else {
                        exchanges.matchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN");
                    }
                    exchanges
                            .pathMatchers("/actuator/**").denyAll()
                            // SockJS/STOMP: the JWT travels in the STOMP CONNECT frame and is
                            // validated by notification-service, so the HTTP handshake must pass.
                            .pathMatchers("/ws/**").permitAll()
                            .pathMatchers("/api/v1/products/**").permitAll()
                            .pathMatchers("/graphql").permitAll()
                            .pathMatchers(HttpMethod.POST, "/api/v1/cart/merge").authenticated()
                            .pathMatchers("/api/v1/cart/**").permitAll()
                            .pathMatchers("/api/v1/orders/**").authenticated()
                            .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                            .anyExchange().authenticated();
                })
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
