package com.robomart.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Value("${gateway.cors.customer-origin:http://localhost:5173}")
    private String customerOrigin;

    @Value("${gateway.cors.admin-origin:http://localhost:5174}")
    private String adminOrigin;

    @Value("${gateway.cors.keycloak-origin:http://localhost:8180}")
    private String keycloakOrigin;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin(stripTrailingSlash(customerOrigin));
        config.addAllowedOrigin(stripTrailingSlash(adminOrigin));
        config.addAllowedOrigin(stripTrailingSlash(keycloakOrigin));
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }

    private static String stripTrailingSlash(String origin) {
        return origin.replaceAll("/+$", "");
    }
}
