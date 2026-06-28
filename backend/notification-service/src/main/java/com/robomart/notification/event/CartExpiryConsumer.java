package com.robomart.notification.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.notification.service.NotificationService;
import com.robomart.notification.service.ProcessedEventService;

@Component
public class CartExpiryConsumer {

    private static final Logger log = LoggerFactory.getLogger(CartExpiryConsumer.class);

    private static final String CONSUMER_GROUP = "notification-cart-expiry-group";

    private final NotificationService notificationService;
    private final ProcessedEventService processedEventService;

    public CartExpiryConsumer(NotificationService notificationService,
                              ProcessedEventService processedEventService) {
        this.notificationService = notificationService;
        this.processedEventService = processedEventService;
    }

    @KafkaListener(topics = "cart.cart.expiry-warning",
                   groupId = CONSUMER_GROUP)
    public void onCartExpiryWarning(CartExpiryWarningEvent event) {
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate cart expiry event: eventId={}", eventId);
            return;
        }

        String cartId = event.getCartId().toString();
        String userId = event.getUserId().toString();
        log.info("Received cart expiry warning: cartId={}, userId={}", cartId, userId);
        notificationService.sendCartExpiryWarning(cartId, userId, event);

        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }
}
