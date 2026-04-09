package com.robomart.notification.unit;

import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.notification.event.InventoryAlertConsumer;
import com.robomart.notification.service.NotificationService;

@ExtendWith(MockitoExtension.class)
class InventoryAlertConsumerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private InventoryAlertConsumer inventoryAlertConsumer;

    @Test
    void shouldSendLowStockAlertWhenStockLowAlertEventReceived() {
        StockLowAlertEvent event = StockLowAlertEvent.newBuilder()
                .setEventId("evt-1")
                .setEventType("STOCK_LOW_ALERT")
                .setAggregateId("42")
                .setAggregateType("InventoryItem")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setProductId("42")
                .setCurrentQuantity(5)
                .setThreshold(10)
                .build();

        inventoryAlertConsumer.onStockLowAlert(event);

        verify(notificationService).sendLowStockAlert("42", 5, 10);
    }

    @Test
    void shouldExtractProductIdAsStringFromEvent() {
        StockLowAlertEvent event = StockLowAlertEvent.newBuilder()
                .setEventId("evt-2")
                .setEventType("STOCK_LOW_ALERT")
                .setAggregateId("100")
                .setAggregateType("InventoryItem")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setProductId("100")
                .setCurrentQuantity(3)
                .setThreshold(20)
                .build();

        inventoryAlertConsumer.onStockLowAlert(event);

        verify(notificationService).sendLowStockAlert("100", 3, 20);
    }
}
