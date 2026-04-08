package com.robomart.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.robomart.notification.client.OrderDetailDto;
import com.robomart.notification.client.OrderServiceClient;
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
    private final EmailService emailService;
    private final NotificationLogRepository notificationLogRepository;
    private final Tracer tracer;

    public NotificationService(OrderServiceClient orderServiceClient,
                               EmailService emailService,
                               NotificationLogRepository notificationLogRepository,
                               Tracer tracer) {
        this.orderServiceClient = orderServiceClient;
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

    private void sendAndLog(String userId, String orderId, NotificationType type, String subject, String body) {
        if (notificationLogRepository.existsByOrderIdAndNotificationType(orderId, type)) {
            log.info("Notification already sent, skipping duplicate: orderId={}, type={}", orderId, type);
            return;
        }

        NotificationLog notificationLog = new NotificationLog();
        notificationLog.setRecipient(userId);
        notificationLog.setOrderId(orderId);
        notificationLog.setNotificationType(type);
        notificationLog.setChannel(NotificationChannel.EMAIL);
        notificationLog.setSubject(subject);
        notificationLog.setContent(body);
        notificationLog.setTraceId(getTraceId());

        try {
            emailService.sendEmail(userId, subject, body);
            notificationLog.setStatus(NotificationStatus.SENT);
        } catch (Exception e) {
            log.error("Failed to send {} notification for orderId={}: {}", type, orderId, e.getMessage());
            notificationLog.setStatus(NotificationStatus.FAILED);
            notificationLog.setErrorMessage(e.getMessage());
        }
        try {
            notificationLogRepository.save(notificationLog);
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate notification skipped (race condition): orderId={}, type={}", orderId, type);
        }
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
