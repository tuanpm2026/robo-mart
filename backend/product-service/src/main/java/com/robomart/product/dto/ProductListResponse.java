package com.robomart.product.dto;

import java.math.BigDecimal;

public record ProductListResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        BigDecimal rating,
        String brand,
        Integer stockQuantity,
        Long categoryId,
        String categoryName,
        String primaryImageUrl
) {
}
