package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.events.cart.CartItemSummary;
import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.notification.client.ProductServiceClient;
import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.NotificationStatus;
import com.robomart.notification.enums.NotificationType;
import com.robomart.notification.repository.NotificationLogRepository;
import com.robomart.notification.service.EmailService;
import com.robomart.test.KafkaContainerConfig;
import com.robomart.test.PostgresContainerConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, KafkaContainerConfig.class, TestKafkaProducerConfig.class})
class NotificationAlertIT {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private ProductServiceClient productServiceClient;

    private CartExpiryWarningEvent buildCartExpiryEvent(String cartId, String userId) {
        return CartExpiryWarningEvent.newBuilder()
                .setEventId("evt-cart-it-" + cartId)
                .setEventType("CART_EXPIRY_WARNING")
                .setAggregateId(cartId)
                .setAggregateType("Cart")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setCartId(cartId)
                .setUserId(userId)
                .setExpiresInSeconds(3600L)
                .setCheckoutUrl("http://localhost:5173/cart")
                .setItems(List.of(
                        CartItemSummary.newBuilder()
                                .setProductId(1L)
                                .setProductName("Integration Test Product")
                                .setPrice("49.99")
                                .setQuantity(2)
                                .setSubtotal("99.98")
                                .build()
                ))
                .build();
    }

    @Test
    void shouldConsumeStockLowAlertEventAndCreateNotificationLog() {
        String productId = "88";
        when(productServiceClient.getProductName(productId)).thenReturn("Widget X");

        StockLowAlertEvent event = StockLowAlertEvent.newBuilder()
                .setEventId("evt-low-stock-it-1")
                .setEventType("STOCK_LOW_ALERT")
                .setAggregateId(productId)
                .setAggregateType("InventoryItem")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setProductId(productId)
                .setCurrentQuantity(3)
                .setThreshold(10)
                .build();

        kafkaTemplate.send("inventory.stock.low-alert", productId, event);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationLog> logs = notificationLogRepository.findAll().stream()
                    .filter(log -> log.getNotificationType() == NotificationType.LOW_STOCK_ALERT)
                    .filter(log -> log.getContent().contains("Widget X"))
                    .toList();
            assertThat(logs).hasSize(1);

            NotificationLog log = logs.getFirst();
            assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(log.getContent()).contains("Current stock: 3 units");
            assertThat(log.getContent()).contains("Threshold: 10 units");
        });

        verify(emailService, atLeastOnce()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void shouldConsumeCartExpiryWarningAndCreateNotificationLog() {
        String cartId = "cart-it-001";
        String userId = "user-it-001";

        CartExpiryWarningEvent event = buildCartExpiryEvent(cartId, userId);
        kafkaTemplate.send("cart.cart.expiry-warning", cartId, event);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationLog> logs = notificationLogRepository.findByOrderId(cartId);
            assertThat(logs).hasSize(1);

            NotificationLog log = logs.getFirst();
            assertThat(log.getNotificationType()).isEqualTo(NotificationType.CART_EXPIRY_WARNING);
            assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(log.getRecipient()).isEqualTo(userId);
            assertThat(log.getContent()).contains("Integration Test Product");
            assertThat(log.getContent()).contains("http://localhost:5173/cart");
        });

        verify(emailService, atLeastOnce()).sendEmail(eq(userId), anyString(), anyString());
    }

    @Test
    void shouldSuppressDuplicateCartExpiryWarning() {
        String cartId = "cart-it-002";
        String userId = "user-it-002";

        CartExpiryWarningEvent event1 = buildCartExpiryEvent(cartId, userId);
        CartExpiryWarningEvent event2 = buildCartExpiryEvent(cartId, userId);

        kafkaTemplate.send("cart.cart.expiry-warning", cartId, event1);

        // Wait for first event to be processed
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationLogRepository.findByOrderId(cartId)).hasSize(1));

        // Send duplicate
        kafkaTemplate.send("cart.cart.expiry-warning", cartId, event2);

        // Verify no duplicate entry after 3 seconds
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationLogRepository.findByOrderId(cartId)).hasSize(1));
    }
}
