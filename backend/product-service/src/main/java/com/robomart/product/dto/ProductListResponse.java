package com.robomart.product.dto;

import java.math.BigDecimal;

public record ProductListResponse(
        Long id,
        String sku,
        String name,
        BigDecimal price,
        BigDecimal rating,
        String brand,
        Integer stockQuantity,
        String categoryName,
        String primaryImageUrl
) {
}
