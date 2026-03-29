package com.robomart.cart.event.consumer;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.repository.CartRepository;
import com.robomart.events.product.ProductUpdatedEvent;

@Component
public class ProductEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductEventConsumer.class);

    private final CartRepository cartRepository;

    public ProductEventConsumer(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    @KafkaListener(
            topics = "product.product.updated",
            groupId = "cart-service-product.product.updated-group"
    )
    public void onProductUpdated(ProductUpdatedEvent event) {
        long productId = event.getProductId();
        BigDecimal newPrice;
        try {
            newPrice = new BigDecimal(event.getPrice());
        } catch (NumberFormatException | NullPointerException e) {
            log.error("Invalid price in PRODUCT_UPDATED event: productId={}, price='{}', eventId={}",
                    productId, event.getPrice(), event.getEventId(), e);
            return;
        }
        String newName = event.getName();

        log.debug("Received PRODUCT_UPDATED event: productId={}, newPrice={}, eventId={}",
                productId, newPrice, event.getEventId());

        int updatedCarts = 0;
        for (Cart cart : cartRepository.findAll()) {
            boolean modified = false;
            for (CartItem item : cart.getItems()) {
                if (item.getProductId().equals(productId)) {
                    item.setPrice(newPrice);
                    item.setProductName(newName);
                    modified = true;
                }
            }
            if (modified) {
                cartRepository.save(cart);
                updatedCarts++;
                log.debug("Updated cart={} with new price for productId={}", cart.getId(), productId);
            }
        }

        log.info("Product price update propagated: productId={}, newPrice={}, cartsUpdated={}",
                productId, newPrice, updatedCarts);
    }
}
