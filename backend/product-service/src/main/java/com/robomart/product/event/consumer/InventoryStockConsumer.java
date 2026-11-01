package com.robomart.product.event.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.events.inventory.StockUpdatedEvent;
import com.robomart.product.exception.ProductNotFoundException;
import com.robomart.product.mapper.ProductEventPayloadMapper;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.OutboxPublisher;

import tools.jackson.databind.ObjectMapper;

/** Applies authoritative snapshots and publishes Product's existing index/cache invalidation event. */
@Component
public class InventoryStockConsumer {
    private final ProductRepository products;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public InventoryStockConsumer(ProductRepository products, OutboxPublisher outbox, ObjectMapper mapper) {
        this.products = products;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    @KafkaListener(id = "productInventoryStock", topics = "inventory.stock.updated",
            groupId = "product-inventory-stock")
    public void onStockUpdated(StockUpdatedEvent event) {
        long productId = Long.parseLong(event.getProductId());
        if (productId <= 0 || event.getAvailableQuantity() < 0 || event.getInventoryVersion() < 0) {
            throw new IllegalArgumentException("Invalid inventory stock snapshot");
        }
        var product = products.findStockProjectionForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (event.getInventoryVersion() <= product.getInventoryVersion()) {
            return;
        }
        product.setStockQuantity(event.getAvailableQuantity());
        product.setInventoryVersion(event.getInventoryVersion());
        // Advance Product's own @Version so search's external-version fencing sees this change.
        products.flush();
        outbox.saveEvent("PRODUCT", Long.toString(productId), "PRODUCT_UPDATED",
                mapper.writeValueAsString(ProductEventPayloadMapper.snapshot(product)));
    }
}
