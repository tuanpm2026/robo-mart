package com.robomart.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {

    @Value("${gateway.services.product-service:http://localhost:8081}")
    private String productServiceUri;

    @Value("${gateway.services.cart-service:http://localhost:8082}")
    private String cartServiceUri;

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("product-service", r -> r
                        .path("/api/v1/products/**")
                        .uri(productServiceUri))
                .route("product-graphql", r -> r
                        .path("/graphql")
                        .uri(productServiceUri))
                .route("cart-service", r -> r
                        .path("/api/v1/cart/**")
                        .uri(cartServiceUri))
                .build();
    }
}
