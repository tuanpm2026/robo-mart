package com.robomart.notification.unit;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.event.OrderEventConsumer;
import com.robomart.notification.service.NotificationService;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private NotificationService notificationService;

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

        verify(notificationService).sendOrderConfirmation("100");
        verify(notificationService).sendPaymentSuccess("100");
    }

    @Test
    void shouldSendPaymentFailureWhenCancelledFromPaymentProcessing() {
        OrderStatusChangedEvent event = buildEvent("200", "PAYMENT_PROCESSING", "CANCELLED");

        orderEventConsumer.onOrderStatusChanged(event);

        verify(notificationService).sendPaymentFailure("200");
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
}
