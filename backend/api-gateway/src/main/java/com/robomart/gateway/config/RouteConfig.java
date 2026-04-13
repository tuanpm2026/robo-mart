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

    @Value("${gateway.services.order-service:http://localhost:8083}")
    private String orderServiceUri;

    @Value("${gateway.services.inventory-service:http://localhost:8084}")
    private String inventoryServiceUri;

    @Value("${gateway.services.notification-service:http://localhost:8087}")
    private String notificationServiceUri;

    @Value("${gateway.services.payment-service:http://localhost:8086}")
    private String paymentServiceUri;

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("notification-websocket", r -> r
                        .path("/ws/**")
                        .uri(notificationServiceUri))
                .route("product-service", r -> r
                        .path("/api/v1/products/**")
                        .uri(productServiceUri))
                .route("product-graphql", r -> r
                        .path("/graphql")
                        .uri(productServiceUri))
                .route("cart-service", r -> r
                        .path("/api/v1/cart/**")
                        .uri(cartServiceUri))
                .route("order-service", r -> r
                        .path("/api/v1/orders/**")
                        .uri(orderServiceUri))
                .route("admin-products", r -> r
                        .path("/api/v1/admin/products/**")
                        .uri(productServiceUri))
                .route("admin-orders", r -> r
                        .path("/api/v1/admin/orders/**")
                        .uri(orderServiceUri))
                .route("admin-inventory", r -> r
                        .path("/api/v1/admin/inventory/**")
                        .uri(inventoryServiceUri))
                .route("admin-reports", r -> r
                        .path("/api/v1/admin/reports/**")
                        .uri(orderServiceUri))
                .route("admin-dlq", r -> r
                        .path("/api/v1/admin/dlq/**")
                        .uri(notificationServiceUri))
                .route("admin-payments", r -> r
                        .path("/api/v1/admin/payments/**")
                        .uri(paymentServiceUri))
                .route("admin-system-health", r -> r
                        .path("/api/v1/admin/system/health")
                        .uri(notificationServiceUri))
                .build();
    }
}
