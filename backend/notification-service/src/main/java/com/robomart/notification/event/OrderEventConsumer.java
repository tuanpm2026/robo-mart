package com.robomart.notification.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.service.NotificationService;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final NotificationService notificationService;

    public OrderEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "order.order.status-changed",
                   groupId = "notification-order-status-group")
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        String newStatus = event.getNewStatus().toString();
        String previousStatus = event.getPreviousStatus().toString();
        String orderId = event.getOrderId().toString();

        log.info("Received order status change: orderId={}, {} -> {}", orderId, previousStatus, newStatus);

        if ("CONFIRMED".equals(newStatus)) {
            notificationService.sendOrderConfirmedNotifications(orderId);
        } else if ("CANCELLED".equals(newStatus) && "PAYMENT_PROCESSING".equals(previousStatus)) {
            notificationService.sendPaymentFailure(orderId);
        }
    }
}
