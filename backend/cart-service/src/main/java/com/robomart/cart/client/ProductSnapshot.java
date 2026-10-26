package com.robomart.cart.client;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The subset of product-service's {@code ProductDetailResponse} the cart relies on. Price and name
 * are always taken from here, never from the client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshot(
        Long id,
        String name,
        BigDecimal price
) {
}
