package com.robomart.inventory.event.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.inventory.service.InventoryService;

/** Replays safely against existing stock; Product never owns Inventory's quantities. */
@Component
public class ProductCreatedConsumer {
    private final InventoryService inventory;

    public ProductCreatedConsumer(InventoryService inventory) {
        this.inventory = inventory;
    }

    @KafkaListener(id = "inventoryProductCreated", topics = "product.product.created",
            groupId = "inventory-product-created", autoStartup = "${robomart.inventory.consumer-enabled:true}")
    public void onProductCreated(ProductCreatedEvent event) {
        inventory.initializeProduct(event.getProductId());
    }
}
