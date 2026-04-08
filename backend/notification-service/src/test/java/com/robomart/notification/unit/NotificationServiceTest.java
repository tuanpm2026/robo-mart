package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderItemDto;
import com.robomart.notification.client.OrderServiceClient;
import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.NotificationChannel;
import com.robomart.notification.enums.NotificationStatus;
import com.robomart.notification.enums.NotificationType;
import com.robomart.notification.repository.NotificationLogRepository;
import com.robomart.notification.service.EmailService;
import com.robomart.notification.service.NotificationService;

import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private OrderServiceClient orderServiceClient;

    @Mock
    private EmailService emailService;

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @Mock
    private Tracer tracer;

    @InjectMocks
    private NotificationService notificationService;

    @Captor
    private ArgumentCaptor<NotificationLog> logCaptor;

    private OrderDetailDto createTestOrder() {
        return new OrderDetailDto(
                1L,
                "user-123",
                Instant.now(),
                new BigDecimal("99.99"),
                "CONFIRMED",
                "123 Main St",
                List.of(
                        new OrderItemDto("Widget", 2, new BigDecimal("29.99"), new BigDecimal("59.98")),
                        new OrderItemDto("Gadget", 1, new BigDecimal("40.01"), new BigDecimal("40.01"))
                )
        );
    }

    @Test
    void shouldSendOrderConfirmationWhenStatusChangedToConfirmed() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).save(logCaptor.capture());
        NotificationLog confirmLog = logCaptor.getAllValues().get(0);
        assertThat(confirmLog.getNotificationType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(confirmLog.getRecipient()).isEqualTo("user-123");
        assertThat(confirmLog.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(confirmLog.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(confirmLog.getOrderId()).isEqualTo("1");
        assertThat(confirmLog.getContent()).contains("Widget").contains("Gadget").contains("99.99");
    }

    @Test
    void shouldIncludeItemsSummaryInOrderConfirmationBody() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).save(logCaptor.capture());
        String content = logCaptor.getAllValues().get(0).getContent();
        assertThat(content).contains("Widget x2");
        assertThat(content).contains("Gadget x1");
        assertThat(content).contains("Total: $99.99");
        assertThat(content).contains("Estimated delivery");
    }

    @Test
    void shouldSendPaymentSuccessWhenOrderConfirmed() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).save(logCaptor.capture());
        NotificationLog paymentLog = logCaptor.getAllValues().get(1);
        assertThat(paymentLog.getNotificationType()).isEqualTo(NotificationType.PAYMENT_SUCCESS);
        assertThat(paymentLog.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(paymentLog.getContent()).contains("99.99");
    }

    @Test
    void shouldSendPaymentFailureWithEmpatheticLanguage() {
        OrderDetailDto order = new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"),
                "CANCELLED", null, List.of());
        when(orderServiceClient.getOrderDetail("2")).thenReturn(order);

        notificationService.sendPaymentFailure("2");

        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog saved = logCaptor.getValue();
        assertThat(saved.getNotificationType()).isEqualTo(NotificationType.PAYMENT_FAILED);
        assertThat(saved.getContent()).contains("Payment couldn't be processed");
        assertThat(saved.getContent()).contains("Your order is saved");
    }

    @Test
    void shouldLogFailedStatusWhenEmailServiceThrows() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());
        doThrow(new RuntimeException("SMTP connection refused"))
                .when(emailService).sendEmail(anyString(), anyString(), anyString());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues()).allMatch(log -> log.getStatus() == NotificationStatus.FAILED);
        assertThat(logCaptor.getAllValues().get(0).getErrorMessage()).isEqualTo("SMTP connection refused");
    }

    @Test
    void shouldSetChannelToEmailForAllNotifications() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues()).allMatch(log -> log.getChannel() == NotificationChannel.EMAIL);
    }
}
