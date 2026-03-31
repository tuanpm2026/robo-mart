package com.robomart.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
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

    public PaymentResult processPayment(String orderId, String userId, BigDecimal amount,
                                        String currency, String idempotencyKey) {
        log.info("Processing payment: orderId={}, amount={} {}, idempotencyKey={}",
                orderId, amount, currency, idempotencyKey);

        // [Fix #4] Validate input
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive, got: " + amount);
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }

        // Step 1: Check idempotency key
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey cached = existingKey.get();
            if (cached.getExpiresAt().isAfter(Instant.now())) {
                // Return cached response
                log.info("Idempotency key found and valid, returning cached response: key={}", idempotencyKey);
                return deserializeResponse(cached.getResponse());
            } else {
                // [Fix #3] Expired — delete atomically in transaction
                log.info("Idempotency key expired, deleting and reprocessing: key={}", idempotencyKey);
                transactionTemplate.executeWithoutResult(status -> {
                    idempotencyKeyRepository.delete(cached);
                    paymentRepository.findByIdempotencyKey(idempotencyKey)
                            .ifPresent(paymentRepository::delete);
                });
            }
        }

        // [Fix #1] Check for existing FAILED payment (allows retry after transient failure)
        Payment payment = paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> {
                    Payment newPayment = new Payment();
                    newPayment.setOrderId(orderId);
                    newPayment.setAmount(amount);
                    newPayment.setCurrency(currency);
                    newPayment.setIdempotencyKey(idempotencyKey);
                    return newPayment;
                });
        payment.setStatus(PaymentStatus.PENDING);

        // Step 3: Call gateway and process within transaction
        try {
            GatewayResult gatewayResult = paymentGateway.processPayment(amount, currency);

            // [Fix #2] Success — save in transaction, catch constraint violation for race condition
            try {
                return transactionTemplate.execute(status -> {
                    payment.setStatus(PaymentStatus.COMPLETED);
                    payment.setTransactionId(gatewayResult.transactionId());
                    Payment savedPayment = paymentRepository.save(payment);

                    // Create idempotency key with cached response
                    PaymentResult result = new PaymentResult(
                            true,
                            "Payment processed successfully",
                            String.valueOf(savedPayment.getId()),
                            gatewayResult.transactionId()
                    );

                    IdempotencyKey newKey = new IdempotencyKey();
                    newKey.setIdempotencyKey(idempotencyKey);
                    newKey.setOrderId(orderId);
                    newKey.setResponse(serializeResponse(result));
                    newKey.setExpiresAt(Instant.now().plus(IDEMPOTENCY_KEY_TTL_HOURS, ChronoUnit.HOURS));
                    idempotencyKeyRepository.save(newKey);

                    // Create outbox event
                    createPaymentProcessedEvent(savedPayment);

                    log.info("Payment completed: paymentId={}, transactionId={}",
                            savedPayment.getId(), gatewayResult.transactionId());
                    return result;
                });
            } catch (DataIntegrityViolationException e) {
                // [Fix #2] Race condition: another request already saved this idempotency key
                log.warn("Race condition detected for idempotency key: {}, returning existing result", idempotencyKey);
                return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey)
                        .map(cached -> deserializeResponse(cached.getResponse()))
                        .orElseThrow(() -> new IllegalStateException(
                                "Race condition on idempotency key but no cached result found: " + idempotencyKey));
            }

        } catch (PaymentTransientException e) {
            // Transient failure — save FAILED payment, do NOT store idempotency key (allow retry)
            log.warn("Transient payment failure: orderId={}, key={}", orderId, idempotencyKey, e);
            transactionTemplate.executeWithoutResult(status -> {
                payment.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(payment);
            });
            throw e;

        } catch (PaymentDeclinedException e) {
            // Permanent failure — save FAILED payment and store idempotency key (prevent re-attempt)
            log.warn("Payment declined: orderId={}, key={}", orderId, idempotencyKey, e);
            transactionTemplate.executeWithoutResult(status -> {
                payment.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(payment);

                PaymentResult failResult = new PaymentResult(
                        false, "Payment declined: " + e.getMessage(), null, null
                );

                IdempotencyKey newKey = new IdempotencyKey();
                newKey.setIdempotencyKey(idempotencyKey);
                newKey.setOrderId(orderId);
                newKey.setResponse(serializeResponse(failResult));
                newKey.setExpiresAt(Instant.now().plus(IDEMPOTENCY_KEY_TTL_HOURS, ChronoUnit.HOURS));
                idempotencyKeyRepository.save(newKey);
            });
            throw e;
        }
    }

    public PaymentResult refundPayment(Long paymentId, String orderId, BigDecimal amount, String reason) {
        log.info("Processing refund: paymentId={}, orderId={}, amount={}, reason={}",
                paymentId, orderId, amount, reason);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new IllegalStateException(
                    String.format("Cannot refund payment %d: status is %s, expected COMPLETED",
                            paymentId, payment.getStatus()));
        }

        // [Fix #5] Validate refund amount does not exceed original payment
        if (amount.compareTo(payment.getAmount()) > 0) {
            throw new IllegalArgumentException(
                    String.format("Refund amount %s exceeds payment amount %s",
                            amount.toPlainString(), payment.getAmount().toPlainString()));
        }

        GatewayResult gatewayResult = paymentGateway.refundPayment(payment.getTransactionId(), amount);

        return transactionTemplate.execute(status -> {
            payment.setStatus(PaymentStatus.REFUNDED);
            Payment savedPayment = paymentRepository.save(payment);

            // Create outbox event for refund
            createPaymentRefundedEvent(savedPayment, gatewayResult.transactionId());

            log.info("Payment refunded: paymentId={}, refundTransactionId={}",
                    paymentId, gatewayResult.transactionId());

            return new PaymentResult(
                    true,
                    "Payment refunded successfully",
                    String.valueOf(savedPayment.getId()),
                    gatewayResult.transactionId()
            );
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

    public record PaymentResult(boolean success, String message, String paymentId, String transactionId) {
    }
}
