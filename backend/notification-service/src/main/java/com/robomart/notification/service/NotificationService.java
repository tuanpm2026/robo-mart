package com.robomart.notification.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.robomart.common.exception.ExternalServiceException;
import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderServiceClient;
import com.robomart.notification.client.ProductServiceClient;
import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.NotificationChannel;
import com.robomart.notification.enums.NotificationStatus;
import com.robomart.notification.enums.NotificationType;
import com.robomart.notification.repository.NotificationLogRepository;

import io.micrometer.tracing.Tracer;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final OrderServiceClient orderServiceClient;
    private final ProductServiceClient productServiceClient;
    private final EmailService emailService;
    private final NotificationLogRepository notificationLogRepository;
    private final Tracer tracer;

    @Value("${notification.admin-email}")
    private String adminEmail;

    /** How long a PENDING claim is honoured before another delivery may take it over. */
    @Value("${notification.email.claim-timeout:PT2M}")
    private Duration claimTimeout = Duration.ofMinutes(2);

    public NotificationService(OrderServiceClient orderServiceClient,
                               ProductServiceClient productServiceClient,
                               EmailService emailService,
                               NotificationLogRepository notificationLogRepository,
                               Tracer tracer) {
        this.orderServiceClient = orderServiceClient;
        this.productServiceClient = productServiceClient;
        this.emailService = emailService;
        this.notificationLogRepository = notificationLogRepository;
        this.tracer = tracer;
    }

    public void sendOrderConfirmedNotifications(String orderId) {
        log.info("Sending CONFIRMED notifications for orderId={}", orderId);
        OrderDetailDto order = orderServiceClient.getOrderDetail(orderId);
        if (order == null) {
            log.warn("Cannot send CONFIRMED notifications for orderId={}: order not found", orderId);
            return;
        }
        String confirmSubject = "Order #" + orderId + " Confirmed";
        sendAndLog(order.userId(), orderId, NotificationType.ORDER_CONFIRMED, confirmSubject, buildOrderConfirmationBody(order));

        String paymentSubject = "Payment Received for Order #" + orderId;
        String paymentBody = "Your payment of $" + order.totalAmount() + " has been successfully processed.";
        sendAndLog(order.userId(), orderId, NotificationType.PAYMENT_SUCCESS, paymentSubject, paymentBody);
    }

    public void sendPaymentFailure(String orderId) {
        log.info("Sending payment failure notification for orderId={}", orderId);
        OrderDetailDto order = orderServiceClient.getOrderDetail(orderId);
        if (order == null) {
            log.warn("Cannot send PAYMENT_FAILED notification for orderId={}: order not found", orderId);
            return;
        }
        String subject = "Payment Issue with Order #" + orderId;
        String body = "Payment couldn't be processed. Your order is saved. Please try again or use a different payment method.";
        sendAndLog(order.userId(), orderId, NotificationType.PAYMENT_FAILED, subject, body);
    }

    public void sendLowStockAlert(String productId, int currentQuantity, int threshold) {
        String productName = productServiceClient.getProductName(productId);
        String subject = "Low Stock Alert: " + productName;
        String body = "Low stock alert!\n\nProduct: " + productName + " (ID: " + productId + ")\n"
                + "Current stock: " + currentQuantity + " units\n"
                + "Threshold: " + threshold + " units\n\n"
                + "Please restock via Admin Dashboard.";

        // No dedup key for admin alerts (processed_events dedups the event); record, then deliver.
        NotificationLog notificationLog = notificationLogRepository.saveAndFlush(
                newPendingLog(adminEmail, null, NotificationType.LOW_STOCK_ALERT, subject, body));
        deliver(notificationLog);
        log.info("Low stock alert sent for productId={}, quantity={}, threshold={}",
                productId, currentQuantity, threshold);
    }

    public void sendCartExpiryWarning(String cartId, String userId, CartExpiryWarningEvent event) {
        String subject = "Your cart is expiring soon!";
        String body = buildCartExpiryBody(event);
        sendAndLog(userId, cartId, NotificationType.CART_EXPIRY_WARNING, subject, body);
    }

    private String buildCartExpiryBody(CartExpiryWarningEvent event) {
        long secondsLeft = event.getExpiresInSeconds();
        long hoursLeft = secondsLeft / 3600;
        long minutesLeft = (secondsLeft % 3600) / 60;
        var sb = new StringBuilder();
        if (secondsLeft < 60) {
            sb.append("Your cart is expiring in less than a minute!\n\n");
        } else {
            sb.append("Your cart is expiring in ").append(hoursLeft).append("h ").append(minutesLeft).append("m!\n\n");
        }
        sb.append("Items in your cart:\n");
        BigDecimal total = BigDecimal.ZERO;
        for (var item : event.getItems()) {
            sb.append("- ").append(item.getProductName())
                    .append(" x").append(item.getQuantity())
                    .append(" — $").append(item.getSubtotal()).append("\n");
            try {
                total = total.add(new BigDecimal(item.getSubtotal().toString()));
            } catch (NumberFormatException ignored) {}
        }
        sb.append("\nTotal: $").append(total.setScale(2, RoundingMode.HALF_UP));
        sb.append("\nCheckout now: ").append(event.getCheckoutUrl());
        return sb.toString();
    }

    /**
     * Claim, send, then record the outcome. A PENDING row is inserted first (the
     * {@code (order_id, notification_type)} unique key is the claim), so a crash or DB failure after
     * sending leaves PENDING rather than nothing. Only SENT short-circuits; FAILED and stale PENDING
     * rows are re-claimed and retried. A send failure is recorded as FAILED and rethrown so the Kafka
     * error handler retries / dead-letters the event and the consumer does not mark it processed.
     */
    private void sendAndLog(String userId, String orderId, NotificationType type, String subject, String body) {
        NotificationLog claimed = claim(userId, orderId, type, subject, body);
        if (claimed != null) {
            deliver(claimed);
        }
    }

    /**
     * @return the claimed row, or {@code null} when the notification was already SENT
     * @throws IllegalStateException when another delivery currently holds a fresh claim (retryable)
     */
    private NotificationLog claim(String userId, String orderId, NotificationType type, String subject, String body) {
        Optional<NotificationLog> existing = notificationLogRepository.findByOrderIdAndNotificationType(orderId, type);
        if (existing.isEmpty()) {
            try {
                return notificationLogRepository.saveAndFlush(newPendingLog(userId, orderId, type, subject, body));
            } catch (DataIntegrityViolationException e) {
                throw new IllegalStateException("Concurrent delivery claim for orderId=" + orderId
                        + ", type=" + type + "; will retry", e);
            }
        }
        NotificationLog row = existing.get();
        if (row.getStatus() == NotificationStatus.SENT) {
            log.info("Notification already sent, skipping duplicate: orderId={}, type={}", orderId, type);
            return null;
        }
        Instant now = Instant.now();
        if (notificationLogRepository.reclaim(row.getId(), now, now.minus(claimTimeout)) == 0) {
            throw new IllegalStateException("Notification delivery in progress for orderId=" + orderId
                    + ", type=" + type + "; will retry");
        }
        log.info("Retrying {} notification for orderId={} (previous status {})", type, orderId, row.getStatus());
        return row;
    }

    private void deliver(NotificationLog claimed) {
        try {
            emailService.sendEmail(claimed.getRecipient(), claimed.getSubject(), claimed.getContent());
        } catch (RuntimeException e) {
            log.error("Failed to send {} notification for orderId={}: {}",
                    claimed.getNotificationType(), claimed.getOrderId(), e.getMessage());
            notificationLogRepository.updateStatus(
                    claimed.getId(), NotificationStatus.FAILED, e.getMessage(), Instant.now());
            throw new ExternalServiceException("Email delivery failed for " + claimed.getNotificationType()
                    + " notification, orderId=" + claimed.getOrderId(), e);
        }
        notificationLogRepository.updateStatus(claimed.getId(), NotificationStatus.SENT, null, Instant.now());
    }

    private NotificationLog newPendingLog(String recipient, String orderId, NotificationType type,
                                          String subject, String body) {
        NotificationLog notificationLog = new NotificationLog();
        notificationLog.setRecipient(recipient);
        notificationLog.setOrderId(orderId);
        notificationLog.setNotificationType(type);
        notificationLog.setChannel(NotificationChannel.EMAIL);
        notificationLog.setStatus(NotificationStatus.PENDING);
        notificationLog.setSubject(subject);
        notificationLog.setContent(body);
        notificationLog.setTraceId(getTraceId());
        return notificationLog;
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }

    private String buildOrderConfirmationBody(OrderDetailDto order) {
        var sb = new StringBuilder();
        sb.append("Your order #").append(order.id()).append(" has been confirmed!\n\n");
        sb.append("Items:\n");
        if (order.items() != null) {
            for (var item : order.items()) {
                sb.append("- ").append(item.productName())
                        .append(" x").append(item.quantity())
                        .append(" — $").append(item.subtotal()).append("\n");
            }
        }
        sb.append("\nTotal: $").append(order.totalAmount());
        sb.append("\n\nEstimated delivery: 3-5 business days");
        return sb.toString();
    }
}
