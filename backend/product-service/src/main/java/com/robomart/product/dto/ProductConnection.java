package com.robomart.product.dto;

import java.util.List;

import com.robomart.product.entity.Product;

public record ProductConnection(
        List<Product> content,
        int totalElements,
        int totalPages,
        int page,
        int size
) {
}
