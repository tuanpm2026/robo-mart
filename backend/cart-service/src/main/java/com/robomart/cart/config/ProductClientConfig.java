package com.robomart.cart.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

import com.robomart.cart.client.ProductServiceClient;

/**
 * Registers the {@code product-service} HTTP service group. Base URL
 * ({@code robomart.product-service.url}, env {@code PRODUCT_SERVICE_URL}) and connect/read timeouts
 * are configured under {@code spring.http.serviceclient.product-service}.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = ProductClientConfig.GROUP, types = ProductServiceClient.class)
public class ProductClientConfig {

    public static final String GROUP = "product-service";
}
