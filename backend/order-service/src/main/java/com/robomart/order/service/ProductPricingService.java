package com.robomart.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.exception.ExternalServiceException;
import com.robomart.order.client.ProductCatalogClient;
import com.robomart.order.client.ProductSnapshot;
import com.robomart.order.exception.OrderProductUnavailableException;

/** Resolves the authoritative name and price of ordered products from product-service. */
@Service
public class ProductPricingService {

    private static final Logger log = LoggerFactory.getLogger(ProductPricingService.class);

    private final ProductCatalogClient productCatalogClient;

    public ProductPricingService(ProductCatalogClient productCatalogClient) {
        this.productCatalogClient = productCatalogClient;
    }

    /**
     * Looks up each distinct product once.
     *
     * @throws OrderProductUnavailableException if a product is unknown, inactive or has no valid price
     * @throws ExternalServiceException if product-service cannot be reached or answers with an error
     */
    public Map<Long, PricedProduct> currentPrices(Collection<Long> productIds) {
        Map<Long, PricedProduct> prices = new LinkedHashMap<>();
        for (Long productId : new LinkedHashSet<>(productIds)) {
            prices.put(productId, fetch(productId));
        }
        return prices;
    }

    private PricedProduct fetch(Long productId) {
        ApiResponse<ProductSnapshot> response;
        try {
            response = productCatalogClient.getProduct(productId);
        } catch (HttpClientErrorException.NotFound e) {
            throw unavailable(productId);
        } catch (RestClientException e) {
            log.error("Product lookup failed for productId={}: {}", productId, e.getMessage());
            throw new ExternalServiceException("Product catalog is temporarily unavailable", e);
        }
        ProductSnapshot product = response != null ? response.data() : null;
        if (product == null || product.price() == null || product.price().signum() <= 0
                || product.name() == null || product.name().isBlank()) {
            log.warn("Product {} has no sellable name/price in the catalog response", productId);
            throw unavailable(productId);
        }
        return new PricedProduct(product.name(), product.price().setScale(2, RoundingMode.HALF_UP));
    }

    private static OrderProductUnavailableException unavailable(Long productId) {
        return new OrderProductUnavailableException("Product " + productId + " is not available");
    }

    public record PricedProduct(String name, BigDecimal unitPrice) {
    }
}
