package com.robomart.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

import com.robomart.order.client.ProductCatalogClient;

/**
 * Registers the declarative product-service client. Base URL and connect/read timeouts are bound
 * from {@code spring.http.serviceclient.product-service.*} (see application.yml).
 */
@Configuration
@ImportHttpServices(group = ProductCatalogClientConfig.GROUP, types = ProductCatalogClient.class)
public class ProductCatalogClientConfig {

    public static final String GROUP = "product-service";
}
