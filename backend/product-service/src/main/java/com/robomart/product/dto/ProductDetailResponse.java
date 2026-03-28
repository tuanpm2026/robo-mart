package com.robomart.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ProductDetailResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        BigDecimal rating,
        String brand,
        Integer stockQuantity,
        CategoryResponse category,
        List<ProductImageResponse> images,
        Instant createdAt,
        Instant updatedAt
) {
}
