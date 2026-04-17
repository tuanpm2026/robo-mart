package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderServiceClient;
import com.robomart.notification.client.ProductServiceClient;
import com.robomart.notification.service.AdminPushService;

@ExtendWith(MockitoExtension.class)
class AdminPushServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private OrderServiceClient orderServiceClient;

    @Mock
    private ProductServiceClient productServiceClient;

    @Mock
    private Tracer tracer;

    @InjectMocks
    private AdminPushService adminPushService;

    @Test
    void pushOrderEvent_sendsPayloadToOrdersTopic() {
        OrderStatusChangedEvent event = mock(OrderStatusChangedEvent.class);
        when(event.getOrderId()).thenReturn("order-123");
        when(event.getNewStatus()).thenReturn("CONFIRMED");
        when(event.getTimestamp()).thenReturn(Instant.ofEpochMilli(1700000000000L));

        OrderDetailDto order = new OrderDetailDto(null, "user-456", null,
                new BigDecimal("99.99"), "CONFIRMED", null, null);
        when(orderServiceClient.getOrderDetail("order-123")).thenReturn(order);

        adminPushService.pushOrderEvent(event);

        ArgumentCaptor<AdminPushService.OrderEventPayload> captor =
                ArgumentCaptor.forClass(AdminPushService.OrderEventPayload.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/orders"), captor.capture(), any(MessageHeaders.class));

        AdminPushService.OrderEventPayload payload = captor.getValue();
        assertThat(payload.eventType()).isEqualTo("ORDER_STATUS_CHANGED");
        assertThat(payload.orderId()).isEqualTo("order-123");
        assertThat(payload.status()).isEqualTo("CONFIRMED");
        assertThat(payload.userId()).isEqualTo("user-456");
        assertThat(payload.total()).isEqualTo("99.99");
    }

    @Test
    void pushInventoryAlert_sendsPayloadToInventoryTopic() {
        StockLowAlertEvent event = mock(StockLowAlertEvent.class);
        when(event.getProductId()).thenReturn("prod-1");
        when(event.getCurrentQuantity()).thenReturn(5);
        when(event.getThreshold()).thenReturn(10);
        when(productServiceClient.getProductName("prod-1")).thenReturn("Wireless Headphone X");

        adminPushService.pushInventoryAlert(event);

        ArgumentCaptor<AdminPushService.InventoryAlertPayload> captor =
                ArgumentCaptor.forClass(AdminPushService.InventoryAlertPayload.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/inventory-alerts"), captor.capture(), any(MessageHeaders.class));

        AdminPushService.InventoryAlertPayload payload = captor.getValue();
        assertThat(payload.eventType()).isEqualTo("INVENTORY_ALERT");
        assertThat(payload.productId()).isEqualTo("prod-1");
        assertThat(payload.productName()).isEqualTo("Wireless Headphone X");
        assertThat(payload.currentStock()).isEqualTo(5);
        assertThat(payload.threshold()).isEqualTo(10);
    }

    @Test
    void pushOrderEvent_whenOrderServiceFails_doesNotPropagateException() {
        OrderStatusChangedEvent event = mock(OrderStatusChangedEvent.class);
        when(event.getOrderId()).thenReturn("order-999");
        when(event.getNewStatus()).thenReturn("CANCELLED");
        when(event.getTimestamp()).thenReturn(Instant.ofEpochMilli(1700000000000L));
        when(orderServiceClient.getOrderDetail("order-999"))
                .thenThrow(new RuntimeException("Service unavailable"));

        assertDoesNotThrow(() -> adminPushService.pushOrderEvent(event));
    }

    @Test
    void pushInventoryAlert_whenProductServiceFails_doesNotPropagateException() {
        StockLowAlertEvent event = mock(StockLowAlertEvent.class);
        when(event.getProductId()).thenReturn("prod-bad");
        when(event.getCurrentQuantity()).thenReturn(3);
        when(event.getThreshold()).thenReturn(10);
        when(productServiceClient.getProductName("prod-bad"))
                .thenThrow(new RuntimeException("Service unavailable"));

        assertDoesNotThrow(() -> adminPushService.pushInventoryAlert(event));
    }
}
