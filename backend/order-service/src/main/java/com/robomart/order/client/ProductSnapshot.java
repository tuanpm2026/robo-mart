package com.robomart.order.client;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The fields of product-service's {@code ProductDetailResponse} that order pricing relies on. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshot(Long id, String name, BigDecimal price) {
}
