package com.robomart.notification.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.service.AdminPushService;
import com.robomart.notification.service.NotificationService;
import com.robomart.notification.service.ProcessedEventService;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private static final String CONSUMER_GROUP = "notification-order-status-group";

    private final NotificationService notificationService;
    private final AdminPushService adminPushService;
    private final ProcessedEventService processedEventService;

    public OrderEventConsumer(NotificationService notificationService,
                              AdminPushService adminPushService,
                              ProcessedEventService processedEventService) {
        this.notificationService = notificationService;
        this.adminPushService = adminPushService;
        this.processedEventService = processedEventService;
    }

    @KafkaListener(topics = "order.order.status-changed",
                   groupId = CONSUMER_GROUP)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate order status event: eventId={}", eventId);
            return;
        }

        String newStatus = event.getNewStatus().toString();
        String previousStatus = event.getPreviousStatus().toString();
        String orderId = event.getOrderId().toString();

        log.info("Received order status change: orderId={}, {} -> {}", orderId, previousStatus, newStatus);

        if ("CONFIRMED".equals(newStatus)) {
            notificationService.sendOrderConfirmedNotifications(orderId);
        } else if ("CANCELLED".equals(newStatus) && "PAYMENT_PROCESSING".equals(previousStatus)) {
            notificationService.sendPaymentFailure(orderId);
        }

        adminPushService.pushOrderEvent(event);

        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }
}
