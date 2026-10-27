package com.robomart.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
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
                        .filters(f -> guarded(f, "product-service"))
                        .uri(productServiceUri))
                .route("product-graphql", r -> r
                        .path("/graphql")
                        .filters(f -> guarded(f, "product-service"))
                        .uri(productServiceUri))
                .route("product-images", r -> r
                        .path("/images/**").and().method("GET")
                        .filters(f -> guarded(f, "product-service"))
                        .uri(productServiceUri))
                .route("cart-service", r -> r
                        .path("/api/v1/cart/**")
                        .filters(f -> guarded(f, "cart-service"))
                        .uri(cartServiceUri))
                .route("order-service", r -> r
                        .path("/api/v1/orders/**")
                        .filters(f -> guarded(f, "order-service"))
                        .uri(orderServiceUri))
                .route("admin-product-audit", r -> r
                        .path("/api/v1/admin/products/audit-logs")
                        .filters(f -> guarded(f.setPath("/api/v1/admin/audit-logs"), "product-service"))
                        .uri(productServiceUri))
                .route("admin-categories", r -> r
                        .path("/api/v1/admin/categories", "/api/v1/admin/categories/**")
                        .filters(f -> guarded(f, "product-service"))
                        .uri(productServiceUri))
                .route("admin-products", r -> r
                        .path("/api/v1/admin/products/**")
                        .filters(f -> guarded(f, "product-service"))
                        .uri(productServiceUri))
                .route("admin-orders", r -> r
                        .path("/api/v1/admin/orders/**")
                        .filters(f -> guarded(f, "order-service"))
                        .uri(orderServiceUri))
                .route("admin-inventory", r -> r
                        .path("/api/v1/admin/inventory/**")
                        .filters(f -> guarded(f, "inventory-service"))
                        .uri(inventoryServiceUri))
                .route("admin-reports", r -> r
                        .path("/api/v1/admin/reports/**")
                        .filters(f -> guarded(f, "order-service"))
                        .uri(orderServiceUri))
                .route("admin-dlq", r -> r
                        .path("/api/v1/admin/dlq/**")
                        .filters(f -> guarded(f, "notification-service"))
                        .uri(notificationServiceUri))
                .route("admin-payments", r -> r
                        .path("/api/v1/admin/payments/**")
                        .filters(f -> guarded(f, "payment-service"))
                        .uri(paymentServiceUri))
                .route("admin-system-health", r -> r
                        .path("/api/v1/admin/system/health")
                        .filters(f -> guarded(f, "notification-service"))
                        .uri(notificationServiceUri))
                .route("admin-audit", r -> r
                        .path("/api/v1/admin/audit-logs")
                        .filters(f -> guarded(f, "notification-service"))
                        .uri(notificationServiceUri))
                .route("admin-reconciliation", r -> r
                        .path("/api/v1/admin/reconciliation", "/api/v1/admin/reconciliation/**")
                        .filters(f -> guarded(f, "notification-service"))
                        .uri(notificationServiceUri))
                .build();
    }
    private static GatewayFilterSpec guarded(GatewayFilterSpec filters, String service) {
        return filters.circuitBreaker(config -> config.setName(service)
                .setFallbackUri("forward:/fallback/" + service));
    }

}
