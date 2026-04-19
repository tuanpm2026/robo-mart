package com.robomart.notification.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import org.springframework.dao.DataIntegrityViolationException;

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

    @Test
    void shouldSkipWhenOrderNotFoundForOrderConfirmed() {
        when(orderServiceClient.getOrderDetail("order-99")).thenReturn(null);

        notificationService.sendOrderConfirmedNotifications("order-99");

        verify(notificationLogRepository, never()).save(any());
    }

    @Test
    void shouldSkipWhenOrderNotFoundForPaymentFailure() {
        when(orderServiceClient.getOrderDetail("order-99")).thenReturn(null);

        notificationService.sendPaymentFailure("order-99");

        verify(notificationLogRepository, never()).save(any());
    }

    @Test
    void shouldSendLowStockAlertAndSaveLog() {
        when(productServiceClient.getProductName("7")).thenReturn("Widget X");
        when(tracer.currentSpan()).thenReturn(null);

        notificationService.sendLowStockAlert("7", 3, 10);

        verify(emailService).sendEmail(anyString(), anyString(), anyString());
        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog log = logCaptor.getValue();
        assertThat(log.getNotificationType()).isEqualTo(NotificationType.LOW_STOCK_ALERT);
        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(log.getSubject()).contains("Widget X");
        assertThat(log.getContent()).contains("Current stock: 3").contains("Threshold: 10");
    }

    @Test
    void shouldMarkLowStockAlertAsFailedWhenEmailThrows() {
        when(productServiceClient.getProductName("7")).thenReturn("Widget X");
        when(tracer.currentSpan()).thenReturn(null);
        doThrow(new RuntimeException("SMTP error")).when(emailService).sendEmail(anyString(), anyString(), anyString());

        notificationService.sendLowStockAlert("7", 3, 10);

        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(logCaptor.getValue().getErrorMessage()).isEqualTo("SMTP error");
    }

    @Test
    void shouldSendCartExpiryWarningWhenNotDuplicate() {
        when(notificationLogRepository.existsByOrderIdAndNotificationType(
                eq("cart-1"), eq(NotificationType.CART_EXPIRY_WARNING))).thenReturn(false);
        when(notificationLogRepository.existsByOrderIdAndNotificationType(
                eq("cart-1"), eq(NotificationType.CART_EXPIRY_WARNING))).thenReturn(false);
        when(tracer.currentSpan()).thenReturn(null);

        CartExpiryWarningEvent event = buildCartExpiryEvent("cart-1", "user-1", 3600L);

        notificationService.sendCartExpiryWarning("cart-1", "user-1", event);

        verify(notificationLogRepository).save(any(NotificationLog.class));
    }

    @Test
    void shouldSkipCartExpiryWarningWhenAlreadySent() {
        when(notificationLogRepository.existsByOrderIdAndNotificationType(
                "cart-1", NotificationType.CART_EXPIRY_WARNING)).thenReturn(true);

        CartExpiryWarningEvent event = buildCartExpiryEvent("cart-1", "user-1", 3600L);

        notificationService.sendCartExpiryWarning("cart-1", "user-1", event);

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void shouldBuildCartExpiryBodyWithLessThanOneMinuteMessage() {
        when(notificationLogRepository.existsByOrderIdAndNotificationType(
                eq("cart-2"), eq(NotificationType.CART_EXPIRY_WARNING))).thenReturn(false);
        when(tracer.currentSpan()).thenReturn(null);

        CartExpiryWarningEvent event = buildCartExpiryEvent("cart-2", "user-2", 30L);

        notificationService.sendCartExpiryWarning("cart-2", "user-2", event);

        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getContent()).contains("less than a minute");
    }

    @Test
    void shouldSkipDuplicateNotificationWhenAlreadySent() {
        OrderDetailDto order = new OrderDetailDto(1L, "user-123", Instant.now(),
                new BigDecimal("50.00"), "CONFIRMED", null, List.of());
        when(orderServiceClient.getOrderDetail("order-dup")).thenReturn(order);
        when(notificationLogRepository.existsByOrderIdAndNotificationType(
                eq("order-dup"), any(NotificationType.class))).thenReturn(true);

        notificationService.sendOrderConfirmedNotifications("order-dup");

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
        verify(notificationLogRepository, never()).save(any());
    }

    @Test
    void shouldHandleDataIntegrityViolationOnSave() {
        OrderDetailDto order = new OrderDetailDto(1L, "user-123", Instant.now(),
                new BigDecimal("50.00"), "CONFIRMED", null, List.of());
        when(orderServiceClient.getOrderDetail("order-race")).thenReturn(order);
        when(notificationLogRepository.existsByOrderIdAndNotificationType(
                anyString(), any())).thenReturn(false);
        when(tracer.currentSpan()).thenReturn(null);
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(notificationLogRepository).save(any(NotificationLog.class));

        // Should not throw — DataIntegrityViolationException is swallowed (race condition guard)
        notificationService.sendPaymentFailure("order-race");
    }

    @Test
    void shouldIncludeTraceIdWhenSpanIsPresent() {
        when(productServiceClient.getProductName("7")).thenReturn("Widget X");
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-abc");

        notificationService.sendLowStockAlert("7", 3, 10);

        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getTraceId()).isEqualTo("trace-abc");
    }

    @Test
    void shouldBuildConfirmationBodyWithNoItemsWhenOrderItemsNull() {
        OrderDetailDto order = new OrderDetailDto(1L, "user-null-items", Instant.now(),
                new BigDecimal("0.00"), "CONFIRMED", null, null);
        when(orderServiceClient.getOrderDetail("order-nil")).thenReturn(order);
        when(notificationLogRepository.existsByOrderIdAndNotificationType(anyString(), any())).thenReturn(false);
        when(tracer.currentSpan()).thenReturn(null);

        notificationService.sendOrderConfirmedNotifications("order-nil");

        verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
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
