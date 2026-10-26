package com.robomart.cart.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.robomart.common.dto.ApiResponse;

/**
 * Declarative client for product-service's public catalog API. Registered as the
 * {@code product-service} HTTP service group (base URL and timeouts under
 * {@code spring.http.serviceclient.product-service.*}).
 */
@HttpExchange(url = "/api/v1/products", accept = "application/json")
public interface ProductServiceClient {

    @GetExchange("/{productId}")
    ApiResponse<ProductSnapshot> getProduct(@PathVariable("productId") Long productId);
}
