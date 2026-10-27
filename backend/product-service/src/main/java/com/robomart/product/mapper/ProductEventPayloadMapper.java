package com.robomart.product.mapper;

import java.util.LinkedHashMap;
import java.util.Map;

import com.robomart.product.entity.Product;

/** The live writer and demo indexer must publish the same complete product snapshot. */
public final class ProductEventPayloadMapper {
    private ProductEventPayloadMapper() { }

    public static Map<String, Object> snapshot(Product product) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", product.getId());
        payload.put("sku", product.getSku());
        payload.put("name", product.getName());
        payload.put("description", product.getDescription() == null ? "" : product.getDescription());
        payload.put("price", product.getPrice());
        payload.put("categoryId", product.getCategory().getId());
        payload.put("categoryName", product.getCategory().getName());
        payload.put("brand", product.getBrand() == null ? "" : product.getBrand());
        payload.put("rating", product.getRating());
        payload.put("stockQuantity", product.getStockQuantity());
        return payload;
    }
}
