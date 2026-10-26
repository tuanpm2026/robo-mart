package com.robomart.cart.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.robomart.cart.client.ProductServiceClient;
import com.robomart.cart.client.ProductSnapshot;
import com.robomart.cart.exception.ProductNotFoundException;
import com.robomart.common.dto.ApiResponse;
import com.robomart.common.exception.ExternalServiceException;

/**
 * Server-authoritative product lookup: the cart never trusts a client-supplied price or name.
 */
@Service
public class ProductCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogService.class);

    private final ProductServiceClient productServiceClient;

    public ProductCatalogService(ProductServiceClient productServiceClient) {
        this.productServiceClient = productServiceClient;
    }

    /**
     * Fetches the current name and price of a product.
     *
     * @throws ProductNotFoundException if product-service does not know the product (404)
     * @throws ExternalServiceException if product-service is unreachable, times out or returns an
     *                                  unusable response
     */
    public ProductSnapshot getProduct(Long productId) {
        ApiResponse<ProductSnapshot> response;
        try {
            response = productServiceClient.getProduct(productId);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductNotFoundException(productId);
        } catch (RestClientException e) {
            log.warn("Product lookup failed for productId={}: {}", productId, e.getMessage());
            throw new ExternalServiceException("Product service unavailable", e);
        }

        ProductSnapshot product = response != null ? response.data() : null;
        if (product == null || product.price() == null || product.name() == null
                || product.price().signum() < 0) {
            log.warn("Unusable product payload for productId={}", productId);
            throw new ExternalServiceException("Product service returned an invalid product");
        }
        return product;
    }
}
