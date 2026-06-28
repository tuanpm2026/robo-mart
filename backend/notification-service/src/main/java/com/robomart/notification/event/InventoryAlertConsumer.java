package com.robomart.notification.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.notification.service.AdminPushService;
import com.robomart.notification.service.NotificationService;
import com.robomart.notification.service.ProcessedEventService;

@Component
public class InventoryAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryAlertConsumer.class);

    private static final String CONSUMER_GROUP = "notification-inventory-alert-group";

    private final NotificationService notificationService;
    private final AdminPushService adminPushService;
    private final ProcessedEventService processedEventService;

    public InventoryAlertConsumer(NotificationService notificationService,
                                  AdminPushService adminPushService,
                                  ProcessedEventService processedEventService) {
        this.notificationService = notificationService;
        this.adminPushService = adminPushService;
        this.processedEventService = processedEventService;
    }

    @KafkaListener(topics = "inventory.stock.low-alert",
                   groupId = CONSUMER_GROUP)
    public void onStockLowAlert(StockLowAlertEvent event) {
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate low stock alert event: eventId={}", eventId);
            return;
        }

        String productId = event.getProductId().toString();
        int currentQuantity = event.getCurrentQuantity();
        int threshold = event.getThreshold();
        log.info("Received low stock alert: productId={}, quantity={}, threshold={}",
                productId, currentQuantity, threshold);
        notificationService.sendLowStockAlert(productId, currentQuantity, threshold);
        adminPushService.pushInventoryAlert(event);

        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }
}
