package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.common.exception.ExternalServiceException;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.event.OrderEventConsumer;
import com.robomart.notification.service.AdminPushService;
import com.robomart.notification.service.NotificationService;
import com.robomart.notification.service.ProcessedEventService;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private AdminPushService adminPushService;

    @Mock
    private ProcessedEventService processedEventService;

    @InjectMocks
    private OrderEventConsumer orderEventConsumer;

    private OrderStatusChangedEvent buildEvent(String orderId, String previousStatus, String newStatus) {
        return OrderStatusChangedEvent.newBuilder()
                .setEventId("evt-1")
                .setEventType("order_status_changed")
                .setAggregateId(orderId)
                .setAggregateType("Order")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setOrderId(orderId)
                .setPreviousStatus(previousStatus)
                .setNewStatus(newStatus)
                .build();
    }

    @Test
    void shouldSendOrderConfirmationAndPaymentSuccessWhenConfirmed() {
        OrderStatusChangedEvent event = buildEvent("100", "PAYMENT_PROCESSING", "CONFIRMED");

        orderEventConsumer.onOrderStatusChanged(event);

        verify(notificationService).sendOrderConfirmedNotifications("100");
    }

    @Test
    void shouldSendPaymentFailureWhenCancelledFromPaymentProcessing() {
        OrderStatusChangedEvent event = buildEvent("200", "PAYMENT_PROCESSING", "CANCELLED");

        orderEventConsumer.onOrderStatusChanged(event);

        verify(notificationService).sendPaymentFailure("200");
        verify(processedEventService).markProcessed("notification-order-status-group", "evt-1");
    }

    @Test
    void shouldSendPaymentFailureWhenCancelledFromPaymentPendingRecovery() {
        OrderStatusChangedEvent event = buildEvent("210", "PAYMENT_PENDING", "CANCELLED");

        orderEventConsumer.onOrderStatusChanged(event);

        verify(notificationService).sendPaymentFailure("210");
        verify(adminPushService).pushOrderEvent(event);
    }

    @Test
    void shouldNotMarkProcessedWhenNotificationDeliveryFails() {
        OrderStatusChangedEvent event = buildEvent("220", "PAYMENT_PENDING", "CANCELLED");
        doThrow(new ExternalServiceException("SMTP down")).when(notificationService).sendPaymentFailure("220");

        assertThatThrownBy(() -> orderEventConsumer.onOrderStatusChanged(event))
                .isInstanceOf(ExternalServiceException.class);

        verify(processedEventService, never()).markProcessed(anyString(), anyString());
    }

    @Test
    void shouldIgnoreNonRelevantStatusChanges() {
        OrderStatusChangedEvent event = buildEvent("300", "PENDING", "INVENTORY_RESERVING");

        orderEventConsumer.onOrderStatusChanged(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldIgnoreCancelledFromNonPaymentProcessingStatus() {
        OrderStatusChangedEvent event = buildEvent("400", "CONFIRMED", "CANCELLED");

        orderEventConsumer.onOrderStatusChanged(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldIgnoreShippedStatusChange() {
        OrderStatusChangedEvent event = buildEvent("500", "CONFIRMED", "SHIPPED");

        orderEventConsumer.onOrderStatusChanged(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldIgnoreDeliveredStatusChange() {
        OrderStatusChangedEvent event = buildEvent("600", "SHIPPED", "DELIVERED");

        orderEventConsumer.onOrderStatusChanged(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldSkipAlreadyProcessedEvent() {
        when(processedEventService.isProcessed("notification-order-status-group", "evt-1"))
                .thenReturn(true);
        OrderStatusChangedEvent event = buildEvent("100", "PAYMENT_PROCESSING", "CONFIRMED");

        orderEventConsumer.onOrderStatusChanged(event);

        // Duplicate event: no notifications sent, no admin push, not re-marked
        verifyNoInteractions(notificationService);
        verifyNoInteractions(adminPushService);
    }
}
