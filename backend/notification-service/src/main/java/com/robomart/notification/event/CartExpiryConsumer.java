package com.robomart.notification.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.notification.service.NotificationService;

@Component
public class CartExpiryConsumer {

    private static final Logger log = LoggerFactory.getLogger(CartExpiryConsumer.class);

    private final NotificationService notificationService;

    public CartExpiryConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "cart.cart.expiry-warning",
                   groupId = "notification-cart-expiry-group")
    public void onCartExpiryWarning(CartExpiryWarningEvent event) {
        String cartId = event.getCartId().toString();
        String userId = event.getUserId().toString();
        log.info("Received cart expiry warning: cartId={}, userId={}", cartId, userId);
        notificationService.sendCartExpiryWarning(cartId, userId, event);
    }
}
