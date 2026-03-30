package com.robomart.security.config;

import com.robomart.security.converter.KeycloakRealmRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared security configuration providing Keycloak integration beans.
 * <p>
 * This configuration does NOT define a SecurityFilterChain or SecurityWebFilterChain.
 * Each consumer module (servlet services, reactive gateway) creates its own filter chain
 * and wires in these shared beans.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public KeycloakRealmRoleConverter keycloakRealmRoleConverter() {
        return new KeycloakRealmRoleConverter();
    }
}
