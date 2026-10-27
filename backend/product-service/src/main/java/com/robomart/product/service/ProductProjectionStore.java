package com.robomart.product.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.entity.Product;
import com.robomart.product.repository.ProductRepository;

/** Materialize a detached authoritative snapshot before any Elasticsearch request. */
@Service
@Transactional(readOnly = true, timeout = 5)
public class ProductProjectionStore {
    private final ProductRepository products;

    public ProductProjectionStore(ProductRepository products) { this.products = products; }

    public ProductDocument load(long id) {
        return snapshot(products.findProjectionById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product projection source missing: " + id)));
    }

    public List<ProductDocument> page(long after, int size) {
        return products.findProjectionPage(after, PageRequest.of(0, size)).stream().map(this::snapshot).toList();
    }

    private ProductDocument snapshot(Product product) {
        if (product.getVersion() == null || product.getVersion() < 0) throw new IllegalStateException("Missing source version");
        var document = new ProductDocument();
        document.setId(product.getId());
        document.setSku(product.getSku());
        document.setName(product.getName());
        document.setDescription(product.getDescription());
        document.setPrice(product.getPrice());
        document.setRating(product.getRating());
        document.setBrand(product.getBrand());
        document.setStockQuantity(product.getStockQuantity());
        document.setCategoryId(product.getCategory().getId());
        document.setCategoryName(product.getCategory().getName());
        document.setCreatedAt(product.getCreatedAt());
        document.setUpdatedAt(product.getUpdatedAt());
        document.setDeleted(!product.isActive());
        document.setSourceVersion(product.getVersion().longValue());
        return document;
    }
}
