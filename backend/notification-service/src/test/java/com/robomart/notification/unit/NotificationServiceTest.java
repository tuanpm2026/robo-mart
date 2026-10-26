package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import com.robomart.common.exception.ExternalServiceException;
import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderItemDto;
import com.robomart.notification.client.OrderServiceClient;
import com.robomart.notification.client.ProductServiceClient;
import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.NotificationChannel;
import com.robomart.notification.enums.NotificationStatus;
import com.robomart.notification.enums.NotificationType;
import com.robomart.notification.repository.NotificationLogRepository;
import com.robomart.notification.service.EmailService;
import com.robomart.notification.service.NotificationService;

import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock
    private OrderServiceClient orderServiceClient;

    @Mock
    private ProductServiceClient productServiceClient;

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

    private final AtomicLong ids = new AtomicLong();

    @BeforeEach
    void setUp() {
        when(notificationLogRepository.findByOrderIdAndNotificationType(anyString(), any()))
                .thenReturn(Optional.empty());
        when(notificationLogRepository.saveAndFlush(any(NotificationLog.class))).thenAnswer(inv -> {
            NotificationLog saved = inv.getArgument(0);
            saved.setId(ids.incrementAndGet());
            return saved;
        });
    }

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

    private NotificationLog existingRow(long id, NotificationStatus status) {
        NotificationLog row = new NotificationLog();
        row.setId(id);
        row.setStatus(status);
        row.setRecipient("user-456");
        row.setOrderId("2");
        row.setNotificationType(NotificationType.PAYMENT_FAILED);
        row.setChannel(NotificationChannel.EMAIL);
        row.setSubject("Payment Issue with Order #2");
        row.setContent("original body");
        return row;
    }

    @Test
    void shouldSendOrderConfirmationWhenStatusChangedToConfirmed() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).saveAndFlush(logCaptor.capture());
        NotificationLog confirmLog = logCaptor.getAllValues().get(0);
        assertThat(confirmLog.getNotificationType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(confirmLog.getRecipient()).isEqualTo("user-123");
        assertThat(confirmLog.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(confirmLog.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(confirmLog.getOrderId()).isEqualTo("1");
        assertThat(confirmLog.getContent()).contains("Widget").contains("Gadget").contains("99.99");
        verify(notificationLogRepository).updateStatus(eq(1L), eq(NotificationStatus.SENT), isNull(), any());
        verify(notificationLogRepository).updateStatus(eq(2L), eq(NotificationStatus.SENT), isNull(), any());
    }

    @Test
    void shouldClaimBeforeSendingAndMarkSentAfterwards() {
        when(orderServiceClient.getOrderDetail("2")).thenReturn(new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"), "CANCELLED", null, List.of()));

        notificationService.sendPaymentFailure("2");

        InOrder order = inOrder(notificationLogRepository, emailService);
        order.verify(notificationLogRepository).saveAndFlush(any(NotificationLog.class));
        order.verify(emailService).sendEmail(eq("user-456"), anyString(), anyString());
        order.verify(notificationLogRepository).updateStatus(eq(1L), eq(NotificationStatus.SENT), isNull(), any());
    }

    @Test
    void shouldIncludeItemsSummaryInOrderConfirmationBody() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).saveAndFlush(logCaptor.capture());
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

        verify(notificationLogRepository, times(2)).saveAndFlush(logCaptor.capture());
        NotificationLog paymentLog = logCaptor.getAllValues().get(1);
        assertThat(paymentLog.getNotificationType()).isEqualTo(NotificationType.PAYMENT_SUCCESS);
        assertThat(paymentLog.getContent()).contains("99.99");
    }

    @Test
    void shouldSendPaymentFailureWithEmpatheticLanguage() {
        OrderDetailDto order = new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"),
                "CANCELLED", null, List.of());
        when(orderServiceClient.getOrderDetail("2")).thenReturn(order);

        notificationService.sendPaymentFailure("2");

        verify(notificationLogRepository).saveAndFlush(logCaptor.capture());
        NotificationLog saved = logCaptor.getValue();
        assertThat(saved.getNotificationType()).isEqualTo(NotificationType.PAYMENT_FAILED);
        assertThat(saved.getContent()).contains("Payment couldn't be processed");
        assertThat(saved.getContent()).contains("Your order is saved");
    }

    @Test
    void shouldMarkFailedAndThrowWhenEmailServiceThrows() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());
        doThrow(new RuntimeException("SMTP connection refused"))
                .when(emailService).sendEmail(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> notificationService.sendOrderConfirmedNotifications("1"))
                .isInstanceOf(ExternalServiceException.class)
                .hasRootCauseMessage("SMTP connection refused");

        verify(notificationLogRepository).updateStatus(
                eq(1L), eq(NotificationStatus.FAILED), eq("SMTP connection refused"), any());
        verify(notificationLogRepository, never()).updateStatus(anyLong(), eq(NotificationStatus.SENT), any(), any());
        // The second notification of the event is not attempted; the redelivery handles both.
        verify(notificationLogRepository, times(1)).saveAndFlush(any(NotificationLog.class));
    }

    @Test
    void shouldShortCircuitWhenAlreadySent() {
        when(orderServiceClient.getOrderDetail("2")).thenReturn(new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"), "CANCELLED", null, List.of()));
        when(notificationLogRepository.findByOrderIdAndNotificationType("2", NotificationType.PAYMENT_FAILED))
                .thenReturn(Optional.of(existingRow(7L, NotificationStatus.SENT)));

        notificationService.sendPaymentFailure("2");

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
        verify(notificationLogRepository, never()).saveAndFlush(any());
        verify(notificationLogRepository, never()).reclaim(anyLong(), any(), any());
    }

    @Test
    void shouldRetryPreviouslyFailedNotificationOnRedelivery() {
        when(orderServiceClient.getOrderDetail("2")).thenReturn(new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"), "CANCELLED", null, List.of()));
        when(notificationLogRepository.findByOrderIdAndNotificationType("2", NotificationType.PAYMENT_FAILED))
                .thenReturn(Optional.of(existingRow(7L, NotificationStatus.FAILED)));
        when(notificationLogRepository.reclaim(eq(7L), any(), any())).thenReturn(1);

        notificationService.sendPaymentFailure("2");

        verify(emailService).sendEmail("user-456", "Payment Issue with Order #2", "original body");
        verify(notificationLogRepository).updateStatus(eq(7L), eq(NotificationStatus.SENT), isNull(), any());
        verify(notificationLogRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldReclaimWithStaleCutoffBeforeNow() {
        when(orderServiceClient.getOrderDetail("2")).thenReturn(new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"), "CANCELLED", null, List.of()));
        when(notificationLogRepository.findByOrderIdAndNotificationType("2", NotificationType.PAYMENT_FAILED))
                .thenReturn(Optional.of(existingRow(7L, NotificationStatus.PENDING)));
        when(notificationLogRepository.reclaim(eq(7L), any(), any())).thenReturn(1);

        notificationService.sendPaymentFailure("2");

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
        verify(notificationLogRepository).reclaim(eq(7L), now.capture(), staleBefore.capture());
        assertThat(staleBefore.getValue()).isBefore(now.getValue());
        verify(emailService).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void shouldThrowRetryableWhenFreshPendingClaimHeldElsewhere() {
        when(orderServiceClient.getOrderDetail("2")).thenReturn(new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"), "CANCELLED", null, List.of()));
        when(notificationLogRepository.findByOrderIdAndNotificationType("2", NotificationType.PAYMENT_FAILED))
                .thenReturn(Optional.of(existingRow(7L, NotificationStatus.PENDING)));
        when(notificationLogRepository.reclaim(eq(7L), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> notificationService.sendPaymentFailure("2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in progress");

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void shouldThrowRetryableWhenConcurrentInsertWinsClaim() {
        when(orderServiceClient.getOrderDetail("2")).thenReturn(new OrderDetailDto(
                2L, "user-456", Instant.now(), new BigDecimal("50.00"), "CANCELLED", null, List.of()));
        when(notificationLogRepository.saveAndFlush(any(NotificationLog.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> notificationService.sendPaymentFailure("2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent delivery claim");

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void shouldSetChannelToEmailForAllNotifications() {
        when(orderServiceClient.getOrderDetail("1")).thenReturn(createTestOrder());

        notificationService.sendOrderConfirmedNotifications("1");

        verify(notificationLogRepository, times(2)).saveAndFlush(logCaptor.capture());
        assertThat(logCaptor.getAllValues()).allMatch(log -> log.getChannel() == NotificationChannel.EMAIL);
    }
}
