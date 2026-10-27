package com.robomart.cart.event.consumer;

import java.math.BigDecimal;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.cart.repository.CartRepository;
import com.robomart.events.product.ProductUpdatedEvent;

@Component
public class ProductEventConsumer {
    private final CartRepository carts;

    public ProductEventConsumer(CartRepository carts) {
        this.carts = carts;
    }

    @KafkaListener(topics = "product.product.updated", groupId = "cart-service-product.product.updated-group")
    public void onProductUpdated(ProductUpdatedEvent event) {
        BigDecimal price = new BigDecimal(event.getPrice());
        if (price.signum() < 0) {
            throw new IllegalArgumentException("Product price must be nonnegative");
        }
        carts.updateProduct(event.getProductId(), event.getName(), price, event.getTimestamp());
    }
}
