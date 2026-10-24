package com.robomart.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.audit.AuditAction;
import com.robomart.common.audit.Auditable;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.dto.ReconciliationSummaryResponse;
import com.robomart.payment.entity.IdempotencyKey;
import com.robomart.payment.entity.OutboxEvent;
import com.robomart.payment.entity.Payment;
import com.robomart.payment.enums.PaymentStatus;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.repository.IdempotencyKeyRepository;
import com.robomart.payment.repository.OutboxEventRepository;
import com.robomart.payment.repository.PaymentRepository;

import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final long IDEMPOTENCY_KEY_TTL_HOURS = 24;

    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final MockPaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public PaymentService(
            PaymentRepository paymentRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            OutboxEventRepository outboxEventRepository,
            MockPaymentGateway paymentGateway,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Auditable(action = AuditAction.CREATE, entityType = "Payment",
            entityIdExpression = "#result?.paymentId?.toString()")
    public PaymentResult processPayment(String orderId, String userId, BigDecimal amount,
                                        String currency, String idempotencyKey) {
        log.info("Processing payment: orderId={}, amount={} {}, idempotencyKey={}",
                orderId, amount, currency, idempotencyKey);

        // [Fix #4] Validate input
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive, got: " + amount);
        }
        if (amount.stripTrailingZeros().scale() > 2 || amount.precision() - amount.scale() > 17) {
            throw new IllegalArgumentException("Payment amount must fit 19 digits with at most 2 decimal places");
        }
        if (currency == null || currency.isBlank() || currency.length() != 3) {
            throw new IllegalArgumentException("Currency must contain exactly 3 characters");
        }

        if (orderId == null || orderId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()
                || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Order, user and idempotency key are required");
        }
        if (orderId.length() > 100 || idempotencyKey.length() > 100 || userId.length() > 100) {
            throw new IllegalArgumentException("Order, user and idempotency key must not exceed 100 characters");
        }
        PaymentAttempt attempt = transactionTemplate.execute(status -> {
            // Serialize across replicas before any gateway call, including retries of a FAILED row.
            paymentRepository.lockPaymentRequest("payment:order:" + orderId);
            paymentRepository.lockPaymentRequest("payment:key:" + idempotencyKey);
            return processLocked(orderId, userId, amount, currency, idempotencyKey);
        });
        if (attempt == null) {
            throw new IllegalStateException("Payment transaction returned no result");
        }
        // Commit FAILED/cached-decline state before propagating the original gRPC failure.
        if (attempt.failure() != null) {
            throw attempt.failure();
        }
        return attempt.result();
    }

    private PaymentAttempt processLocked(String orderId, String userId, BigDecimal amount,
            String currency, String idempotencyKey) {
        Optional<Payment> existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existingPayment.isEmpty()) {
            existingPayment = paymentRepository.findByOrderId(orderId);
        }
        if (existingPayment.isPresent()) {
            Payment previous = existingPayment.get();
            if (!previous.getOrderId().equals(orderId) || !previous.getIdempotencyKey().equals(idempotencyKey)
                    || previous.getAmount().compareTo(amount) != 0 || !previous.getCurrency().equals(currency)
                    || (previous.getUserId() != null && !previous.getUserId().equals(userId))) {
                throw new IllegalArgumentException("Idempotency key/order is bound to a different payment request");
            }
            if (previous.getStatus() == PaymentStatus.REFUNDED) {
                throw new PaymentDeclinedException("Payment has already been refunded");
            }
            if (previous.getStatus() == PaymentStatus.COMPLETED) {
                return new PaymentAttempt(completedResult(previous), null);
            }
        }
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey cached = existingKey.get();
            if (!cached.getOrderId().equals(orderId)) {
                throw new IllegalArgumentException("Idempotency key is bound to a different order");
            }
            // TTL bounds the response cache, never permission to charge a settled request again.
            PaymentResult result = deserializeResponse(cached.getResponse());
            if (!result.success()) {
                throw new PaymentDeclinedException(result.message());
            }
            return new PaymentAttempt(result, null);
        }
        Payment payment = existingPayment.orElseGet(
                () -> newPayment(orderId, userId, amount, currency, idempotencyKey));
        try {
            GatewayResult gatewayResult = paymentGateway.processPayment(
                    amount, currency, idempotencyKey, orderId, userId);
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setTransactionId(gatewayResult.transactionId());
            Payment saved = paymentRepository.save(payment);
            PaymentResult result = completedResult(saved);
            cacheResult(orderId, idempotencyKey, result);
            createPaymentProcessedEvent(saved);
            return new PaymentAttempt(result, null);
        } catch (PaymentTransientException e) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            return new PaymentAttempt(null, e);
        } catch (PaymentDeclinedException e) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            cacheResult(orderId, idempotencyKey,
                    new PaymentResult(false, "Payment declined: " + e.getMessage(), null, null));
            return new PaymentAttempt(null, e);
        }
    }

    private Payment newPayment(String orderId, String userId, BigDecimal amount,
            String currency, String idempotencyKey) {
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setUserId(userId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setIdempotencyKey(idempotencyKey);
        return payment;
    }

    private PaymentResult completedResult(Payment payment) {
        return new PaymentResult(true, "Payment processed successfully", String.valueOf(payment.getId()),
                payment.getTransactionId());
    }

    private void cacheResult(String orderId, String idempotencyKey, PaymentResult result) {
        IdempotencyKey cached = new IdempotencyKey();
        cached.setIdempotencyKey(idempotencyKey);
        cached.setOrderId(orderId);
        cached.setResponse(serializeResponse(result));
        cached.setExpiresAt(Instant.now().plus(IDEMPOTENCY_KEY_TTL_HOURS, ChronoUnit.HOURS));
        idempotencyKeyRepository.save(cached);
    }

    private record PaymentAttempt(PaymentResult result, RuntimeException failure) {
    }

    @Auditable(action = AuditAction.UPDATE, entityType = "PAYMENT",
            entityIdExpression = "#result?.paymentId?.toString()")
    public PaymentResult refundPayment(Long paymentId, String orderId, BigDecimal amount, String reason) {
        log.info("Processing refund: paymentId={}, orderId={}, amount={}, reason={}",
                paymentId, orderId, amount, reason);

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 2 || amount.precision() - amount.scale() > 17) {
            throw new IllegalArgumentException("Refund amount must fit 19 digits with at most 2 decimal places");
        }
        return transactionTemplate.execute(status -> {
            paymentRepository.lockPaymentRequest("payment:order:" + orderId);
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
            if (!payment.getOrderId().equals(orderId)) {
                throw new IllegalArgumentException("Payment does not belong to the requested order");
            }
            paymentRepository.lockPaymentRequest("payment:key:" + payment.getIdempotencyKey());
            if (payment.getStatus() == PaymentStatus.REFUNDED) {
                // Legacy refunded rows predate the durable refund receipt column. Never refund again;
                // an empty receipt means the historical refund transaction ID is unavailable.
                return new PaymentResult(true, "Payment already refunded", String.valueOf(payment.getId()),
                        payment.getRefundTransactionId() == null ? "" : payment.getRefundTransactionId());
            }
            if (payment.getStatus() != PaymentStatus.COMPLETED) {
                throw new IllegalStateException(String.format(
                        "Cannot refund payment %d: status is %s, expected COMPLETED", paymentId, payment.getStatus()));
            }
            if (amount.compareTo(payment.getAmount()) > 0) {
                throw new IllegalArgumentException(String.format("Refund amount %s exceeds payment amount %s",
                        amount.toPlainString(), payment.getAmount().toPlainString()));
            }
            GatewayResult gatewayResult = paymentGateway.refundPayment(payment.getTransactionId(), amount);
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setRefundTransactionId(gatewayResult.transactionId());
            Payment savedPayment = paymentRepository.save(payment);
            createPaymentRefundedEvent(savedPayment, gatewayResult.transactionId());
            return new PaymentResult(true, "Payment refunded successfully", String.valueOf(savedPayment.getId()),
                    gatewayResult.transactionId());
        });
    }

    private void createPaymentProcessedEvent(Payment payment) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("paymentId", String.valueOf(payment.getId()));
            payload.put("orderId", payment.getOrderId());
            payload.put("amount", payment.getAmount().toPlainString());
            payload.put("transactionId", payment.getTransactionId());
            payload.put("status", payment.getStatus().name());

            OutboxEvent event = new OutboxEvent(
                    "Payment",
                    String.valueOf(payment.getId()),
                    "payment_processed",
                    objectMapper.writeValueAsString(payload)
            );
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to create payment_processed outbox event", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }
    }

    private void createPaymentRefundedEvent(Payment payment, String refundTransactionId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("paymentId", String.valueOf(payment.getId()));
            payload.put("orderId", payment.getOrderId());
            payload.put("amount", payment.getAmount().toPlainString());
            payload.put("refundTransactionId", refundTransactionId);

            OutboxEvent event = new OutboxEvent(
                    "Payment",
                    String.valueOf(payment.getId()),
                    "payment_refunded",
                    objectMapper.writeValueAsString(payload)
            );
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to create payment_refunded outbox event", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }
    }

    private String serializeResponse(PaymentResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payment result", e);
        }
    }

    private PaymentResult deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, PaymentResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached payment result", e);
        }
    }

    public Optional<Payment> findByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    public ReconciliationSummaryResponse getReconciliationSummary() {
        List<ReconciliationSummaryResponse.OrderPaymentSummary> payments = paymentRepository.findAll().stream()
                .map(p -> new ReconciliationSummaryResponse.OrderPaymentSummary(
                        p.getOrderId(),
                        p.getStatus().name(),
                        p.getAmount() != null ? p.getAmount().toPlainString() : null))
                .toList();
        return new ReconciliationSummaryResponse(payments, java.time.Instant.now());
    }

    public record PaymentResult(boolean success, String message, String paymentId, String transactionId) {
    }
}
