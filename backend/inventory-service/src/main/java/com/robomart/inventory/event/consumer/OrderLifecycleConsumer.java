package com.robomart.inventory.event.consumer;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.order.OrderCancelledEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.inventory.service.OrderLifecycleService;

@Component
@KafkaListener(id = "inventoryOrderLifecycle", groupId = "${robomart.inventory.consumer-group:inventory-order-lifecycle}",
        topics = {"order.order.status-changed", "order.order.cancelled"},
        autoStartup = "${robomart.inventory.consumer-enabled:true}")
public class OrderLifecycleConsumer {
    private final OrderLifecycleService lifecycle;

    public OrderLifecycleConsumer(OrderLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @KafkaHandler
    public void onStatusChanged(OrderStatusChangedEvent event) {
        lifecycle.handle(event.getEventId().toString(), event.getOrderId().toString(), event.getNewStatus().toString());
    }

    @KafkaHandler
    public void onCancelled(OrderCancelledEvent event) {
        lifecycle.handle(event.getEventId().toString(), event.getOrderId().toString(), "CANCELLED");
    }

    @KafkaHandler(isDefault = true)
    public void unsupported(Object event) {
        throw new IllegalArgumentException("Unsupported inventory order lifecycle event");
    }
}
