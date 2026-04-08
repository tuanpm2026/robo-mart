package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
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

import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderItemDto;
import com.robomart.notification.client.OrderServiceClient;
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
class NotificationIntegrationIT {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private OrderServiceClient orderServiceClient;

    private OrderDetailDto createTestOrder(String orderId, String userId) {
        return new OrderDetailDto(
                Long.parseLong(orderId),
                userId,
                Instant.now(),
                new BigDecimal("149.99"),
                "CONFIRMED",
                "456 Oak Ave",
                List.of(new OrderItemDto("Test Product", 3, new BigDecimal("49.99"), new BigDecimal("149.97")))
        );
    }

    @Test
    void shouldConsumeOrderConfirmedEventAndCreateNotificationLog() {
        String orderId = "1001";
        org.mockito.Mockito.when(orderServiceClient.getOrderDetail(orderId))
                .thenReturn(createTestOrder(orderId, "user-integration-1"));

        OrderStatusChangedEvent event = OrderStatusChangedEvent.newBuilder()
                .setEventId("evt-it-1")
                .setEventType("order_status_changed")
                .setAggregateId(orderId)
                .setAggregateType("Order")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setOrderId(orderId)
                .setPreviousStatus("PAYMENT_PROCESSING")
                .setNewStatus("CONFIRMED")
                .build();

        kafkaTemplate.send("order.order.status-changed", orderId, event);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationLog> logs = notificationLogRepository.findByOrderId(orderId);
            assertThat(logs).hasSizeGreaterThanOrEqualTo(2);

            assertThat(logs).anyMatch(log ->
                    log.getNotificationType() == NotificationType.ORDER_CONFIRMED
                    && log.getStatus() == NotificationStatus.SENT
                    && log.getRecipient().equals("user-integration-1"));

            assertThat(logs).anyMatch(log ->
                    log.getNotificationType() == NotificationType.PAYMENT_SUCCESS
                    && log.getStatus() == NotificationStatus.SENT);
        });

        verify(emailService, atLeastOnce()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void shouldConsumePaymentFailureEventAndCreateNotificationLog() {
        String orderId = "1002";
        org.mockito.Mockito.when(orderServiceClient.getOrderDetail(orderId))
                .thenReturn(createTestOrder(orderId, "user-integration-2"));

        OrderStatusChangedEvent event = OrderStatusChangedEvent.newBuilder()
                .setEventId("evt-it-2")
                .setEventType("order_status_changed")
                .setAggregateId(orderId)
                .setAggregateType("Order")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setOrderId(orderId)
                .setPreviousStatus("PAYMENT_PROCESSING")
                .setNewStatus("CANCELLED")
                .build();

        kafkaTemplate.send("order.order.status-changed", orderId, event);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationLog> logs = notificationLogRepository.findByOrderId(orderId);
            assertThat(logs).hasSize(1);

            NotificationLog log = logs.getFirst();
            assertThat(log.getNotificationType()).isEqualTo(NotificationType.PAYMENT_FAILED);
            assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(log.getRecipient()).isEqualTo("user-integration-2");
            assertThat(log.getContent()).contains("Payment couldn't be processed");
        });
    }

    @Test
    void shouldNotCreateNotificationForIrrelevantStatusChange() {
        String orderId = "1003";

        OrderStatusChangedEvent event = OrderStatusChangedEvent.newBuilder()
                .setEventId("evt-it-3")
                .setEventType("order_status_changed")
                .setAggregateId(orderId)
                .setAggregateType("Order")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setOrderId(orderId)
                .setPreviousStatus("CONFIRMED")
                .setNewStatus("SHIPPED")
                .build();

        kafkaTemplate.send("order.order.status-changed", orderId, event);

        // Verify no notification is created — condition must hold for 3 seconds
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationLogRepository.findByOrderId(orderId)).isEmpty());
    }
}
