package com.robomart.order.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.robomart.common.dto.ApiResponse;

/**
 * Read-only view of product-service's public catalog API, used to price orders server-side. The
 * endpoint answers 404 for unknown and for deactivated products. Base URL and timeouts come from the
 * {@code product-service} HTTP service group ({@code spring.http.serviceclient.product-service.*}).
 */
@HttpExchange("/api/v1/products")
public interface ProductCatalogClient {

    @GetExchange("/{productId}")
    ApiResponse<ProductSnapshot> getProduct(@PathVariable("productId") Long productId);
}
