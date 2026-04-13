package com.robomart.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderServiceClient;
import com.robomart.notification.client.ProductServiceClient;
import com.robomart.notification.web.SystemHealthResponse;

@Service
public class AdminPushService {

    private static final Logger log = LoggerFactory.getLogger(AdminPushService.class);

    private static final String TOPIC_ORDERS = "/topic/orders";
    private static final String TOPIC_INVENTORY_ALERTS = "/topic/inventory-alerts";
    private static final String TOPIC_SYSTEM_HEALTH = "/topic/system-health";

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderServiceClient orderServiceClient;
    private final ProductServiceClient productServiceClient;

    public AdminPushService(SimpMessagingTemplate messagingTemplate,
                            OrderServiceClient orderServiceClient,
                            ProductServiceClient productServiceClient) {
        this.messagingTemplate = messagingTemplate;
        this.orderServiceClient = orderServiceClient;
        this.productServiceClient = productServiceClient;
    }

    public void pushOrderEvent(OrderStatusChangedEvent event) {
        try {
            String orderId = event.getOrderId().toString();
            String status = event.getNewStatus().toString();
            String timestamp = event.getTimestamp().toString();

            String userId = null;
            String total = null;
            OrderDetailDto order = orderServiceClient.getOrderDetail(orderId);
            if (order != null) {
                userId = order.userId();
                total = order.totalAmount() != null ? order.totalAmount().toString() : null;
            }

            OrderEventPayload payload = new OrderEventPayload(
                    "ORDER_STATUS_CHANGED", orderId, status, userId, total, timestamp);
            messagingTemplate.convertAndSend(TOPIC_ORDERS, payload);
            log.debug("Pushed order event to {}: orderId={}, status={}", TOPIC_ORDERS, orderId, status);
        } catch (Exception e) {
            log.warn("Failed to push order event to WebSocket: {}", e.getMessage());
        }
    }

    public void pushInventoryAlert(StockLowAlertEvent event) {
        try {
            String productId = event.getProductId().toString();
            int currentStock = event.getCurrentQuantity();
            int threshold = event.getThreshold();
            String timestamp = java.time.Instant.now().toString();

            String productName = productServiceClient.getProductName(productId);

            InventoryAlertPayload payload = new InventoryAlertPayload(
                    "INVENTORY_ALERT", productId, productName, currentStock, threshold, timestamp);
            messagingTemplate.convertAndSend(TOPIC_INVENTORY_ALERTS, payload);
            log.debug("Pushed inventory alert to {}: productId={}, stock={}", TOPIC_INVENTORY_ALERTS, productId, currentStock);
        } catch (Exception e) {
            log.warn("Failed to push inventory alert to WebSocket: {}", e.getMessage());
        }
    }

    public void pushSystemHealth(SystemHealthResponse health) {
        try {
            messagingTemplate.convertAndSend(TOPIC_SYSTEM_HEALTH, health);
            log.debug("Pushed system health update: {} services", health.services().size());
        } catch (Exception e) {
            log.warn("Failed to push system health to WebSocket: {}", e.getMessage());
        }
    }

    public record OrderEventPayload(
            String eventType,
            String orderId,
            String status,
            String userId,
            String total,
            String timestamp) {
    }

    public record InventoryAlertPayload(
            String eventType,
            String productId,
            String productName,
            int currentStock,
            int threshold,
            String timestamp) {
    }
}
