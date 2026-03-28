package com.robomart.product.dto;

public record ProductImageResponse(
        Long id,
        String imageUrl,
        String altText,
        Integer displayOrder
) {
}
