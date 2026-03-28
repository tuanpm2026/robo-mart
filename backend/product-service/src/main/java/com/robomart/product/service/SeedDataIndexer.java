package com.robomart.product.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.product.entity.Product;
import com.robomart.product.repository.ProductRepository;

import tools.jackson.databind.ObjectMapper;

@Component
@Profile("demo")
public class SeedDataIndexer {

    private static final Logger log = LoggerFactory.getLogger(SeedDataIndexer.class);

    private final ProductRepository productRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public SeedDataIndexer(ProductRepository productRepository,
                           OutboxPublisher outboxPublisher,
                           ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void indexSeedData() {
        List<Product> products = productRepository.findAll();
        log.info("Indexing {} seed products via outbox events", products.size());

        for (Product product : products) {
            String payload = buildPayload(product);
            outboxPublisher.saveEvent(
                    "PRODUCT",
                    String.valueOf(product.getId()),
                    "PRODUCT_CREATED",
                    payload
            );
        }

        log.info("Created {} outbox events for seed data indexing", products.size());
    }

    private String buildPayload(Product product) {
        try {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("id", product.getId());
            payload.put("sku", product.getSku());
            payload.put("name", product.getName());
            payload.put("description", product.getDescription());
            payload.put("price", product.getPrice());
            payload.put("categoryId", product.getCategory().getId());
            payload.put("categoryName", product.getCategory().getName());
            payload.put("brand", product.getBrand());
            payload.put("rating", product.getRating());
            payload.put("stockQuantity", product.getStockQuantity());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize product payload for id=" + product.getId(), e);
        }
    }
}
