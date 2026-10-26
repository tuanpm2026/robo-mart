package com.robomart.notification.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.robomart.common.exception.ExternalServiceException;
import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.events.cart.CartItemSummary;
import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderServiceClient;
import com.robomart.notification.client.ProductServiceClient;
import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.NotificationStatus;
import com.robomart.notification.enums.NotificationType;
import com.robomart.notification.repository.NotificationLogRepository;
import com.robomart.notification.service.EmailService;
import com.robomart.notification.service.NotificationService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceExtendedTest {

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

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    @InjectMocks
    private NotificationService notificationService;

    @Captor
    private ArgumentCaptor<NotificationLog> logCaptor;

    @BeforeEach
    void setUp() {
        // @Value fields are not populated by Mockito — set them explicitly
        ReflectionTestUtils.setField(notificationService, "adminEmail", "admin@test.com");
        when(notificationLogRepository.findByOrderIdAndNotificationType(anyString(), any()))
                .thenReturn(Optional.empty());
        when(notificationLogRepository.saveAndFlush(any(NotificationLog.class))).thenAnswer(inv -> {
            NotificationLog saved = inv.getArgument(0);
            saved.setId(42L);
            return saved;
        });
    }

    @Test
    void shouldSkipWhenOrderNotFoundForOrderConfirmed() {
        when(orderServiceClient.getOrderDetail("order-99")).thenReturn(null);

        notificationService.sendOrderConfirmedNotifications("order-99");

        verify(notificationLogRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldSkipWhenOrderNotFoundForPaymentFailure() {
        when(orderServiceClient.getOrderDetail("order-99")).thenReturn(null);

        notificationService.sendPaymentFailure("order-99");

        verify(notificationLogRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldSendLowStockAlertAndSaveLog() {
        when(productServiceClient.getProductName("7")).thenReturn("Widget X");
        when(tracer.currentSpan()).thenReturn(null);

        notificationService.sendLowStockAlert("7", 3, 10);

        verify(emailService).sendEmail(eq("admin@test.com"), anyString(), anyString());
        verify(notificationLogRepository).saveAndFlush(logCaptor.capture());
        NotificationLog log = logCaptor.getValue();
        assertThat(log.getNotificationType()).isEqualTo(NotificationType.LOW_STOCK_ALERT);
        assertThat(log.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(log.getSubject()).contains("Widget X");
        assertThat(log.getContent()).contains("Current stock: 3").contains("Threshold: 10");
        verify(notificationLogRepository).updateStatus(eq(42L), eq(NotificationStatus.SENT), isNull(), any());
    }

    @Test
    void shouldMarkLowStockAlertAsFailedAndThrowWhenEmailThrows() {
        when(productServiceClient.getProductName("7")).thenReturn("Widget X");
        when(tracer.currentSpan()).thenReturn(null);
        doThrow(new RuntimeException("SMTP error")).when(emailService).sendEmail(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> notificationService.sendLowStockAlert("7", 3, 10))
                .isInstanceOf(ExternalServiceException.class);

        verify(notificationLogRepository).updateStatus(eq(42L), eq(NotificationStatus.FAILED), eq("SMTP error"), any());
    }

    @Test
    void shouldSendCartExpiryWarningWhenNotDuplicate() {
        when(tracer.currentSpan()).thenReturn(null);

        CartExpiryWarningEvent event = buildCartExpiryEvent("cart-1", "user-1", 3600L);

        notificationService.sendCartExpiryWarning("cart-1", "user-1", event);

        verify(notificationLogRepository).saveAndFlush(any(NotificationLog.class));
        verify(emailService).sendEmail(eq("user-1"), anyString(), anyString());
    }

    @Test
    void shouldSkipCartExpiryWarningWhenAlreadySent() {
        NotificationLog sent = new NotificationLog();
        sent.setId(5L);
        sent.setStatus(NotificationStatus.SENT);
        when(notificationLogRepository.findByOrderIdAndNotificationType(
                "cart-1", NotificationType.CART_EXPIRY_WARNING)).thenReturn(Optional.of(sent));

        CartExpiryWarningEvent event = buildCartExpiryEvent("cart-1", "user-1", 3600L);

        notificationService.sendCartExpiryWarning("cart-1", "user-1", event);

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void shouldBuildCartExpiryBodyWithLessThanOneMinuteMessage() {
        when(tracer.currentSpan()).thenReturn(null);

        CartExpiryWarningEvent event = buildCartExpiryEvent("cart-2", "user-2", 30L);

        notificationService.sendCartExpiryWarning("cart-2", "user-2", event);

        verify(notificationLogRepository).saveAndFlush(logCaptor.capture());
        assertThat(logCaptor.getValue().getContent()).contains("less than a minute");
    }

    @Test
    void shouldSkipDuplicateNotificationWhenAlreadySent() {
        OrderDetailDto order = new OrderDetailDto(1L, "user-123", Instant.now(),
                new BigDecimal("50.00"), "CONFIRMED", null, List.of());
        when(orderServiceClient.getOrderDetail("order-dup")).thenReturn(order);
        NotificationLog sent = new NotificationLog();
        sent.setId(5L);
        sent.setStatus(NotificationStatus.SENT);
        when(notificationLogRepository.findByOrderIdAndNotificationType(eq("order-dup"), any(NotificationType.class)))
                .thenReturn(Optional.of(sent));

        notificationService.sendOrderConfirmedNotifications("order-dup");

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
        verify(notificationLogRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldIncludeTraceIdWhenSpanIsPresent() {
        when(productServiceClient.getProductName("7")).thenReturn("Widget X");
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-abc");

        notificationService.sendLowStockAlert("7", 3, 10);

        verify(notificationLogRepository).saveAndFlush(logCaptor.capture());
        assertThat(logCaptor.getValue().getTraceId()).isEqualTo("trace-abc");
    }

    @Test
    void shouldBuildConfirmationBodyWithNoItemsWhenOrderItemsNull() {
        OrderDetailDto order = new OrderDetailDto(1L, "user-null-items", Instant.now(),
                new BigDecimal("0.00"), "CONFIRMED", null, null);
        when(orderServiceClient.getOrderDetail("order-nil")).thenReturn(order);
        when(tracer.currentSpan()).thenReturn(null);

        notificationService.sendOrderConfirmedNotifications("order-nil");

        verify(notificationLogRepository, times(2)).saveAndFlush(any(NotificationLog.class));
    }

    private CartExpiryWarningEvent buildCartExpiryEvent(String cartId, String userId, long expiresInSeconds) {
        return CartExpiryWarningEvent.newBuilder()
                .setEventId("evt-1")
                .setEventType("CART_EXPIRY_WARNING")
                .setAggregateId(cartId)
                .setAggregateType("Cart")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setCartId(cartId)
                .setUserId(userId)
                .setExpiresInSeconds(expiresInSeconds)
                .setCheckoutUrl("http://localhost:5173/cart")
                .setItems(List.of(
                        CartItemSummary.newBuilder()
                                .setProductId(1L)
                                .setProductName("Test Product")
                                .setPrice("19.99")
                                .setQuantity(2)
                                .setSubtotal("39.98")
                                .build()
                ))
                .build();
    }
}
