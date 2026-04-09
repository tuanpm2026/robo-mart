package com.robomart.notification.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.notification.service.NotificationService;

@Component
public class InventoryAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryAlertConsumer.class);

    private final NotificationService notificationService;

    public InventoryAlertConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "inventory.stock.low-alert",
                   groupId = "notification-inventory-alert-group")
    public void onStockLowAlert(StockLowAlertEvent event) {
        String productId = event.getProductId().toString();
        int currentQuantity = event.getCurrentQuantity();
        int threshold = event.getThreshold();
        log.info("Received low stock alert: productId={}, quantity={}, threshold={}",
                productId, currentQuantity, threshold);
        notificationService.sendLowStockAlert(productId, currentQuantity, threshold);
    }
}
