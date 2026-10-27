package com.robomart.payment.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.entity.IdempotencyKey;
import com.robomart.payment.entity.Payment;
import com.robomart.payment.entity.RefundIntent;
import com.robomart.payment.enums.PaymentStatus;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.repository.IdempotencyKeyRepository;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.repository.RefundIntentRepository;
import com.robomart.payment.service.PaymentService.PaymentResult;

import tools.jackson.databind.ObjectMapper;

/** Short committed transactions surround provider calls; tokens fence delayed finalizers. */
@Service
public class PaymentIntentStore {
    private final PaymentRepository payments;
    private final IdempotencyKeyRepository keys;
    private final RefundIntentRepository refunds;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public PaymentIntentStore(PaymentRepository payments, IdempotencyKeyRepository keys,
            RefundIntentRepository refunds, JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate template) {
        this.payments = payments;
        this.keys = keys;
        this.refunds = refunds;
        this.jdbc = jdbc;
        this.mapper = mapper;
        transactions = new TransactionTemplate(template.getTransactionManager());
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ChargeAttempt prepareCharge(String order, String user, BigDecimal amount, String currency, String key) {
        return inTransaction(() -> {
            lock(order, key);
            Payment payment = payments.findByIdempotencyKey(key).orElseGet(() -> payments.findByOrderId(order)
                    .orElse(null));
            if (payment != null) {
                if (!payment.getOrderId().equals(order) || !payment.getIdempotencyKey().equals(key)
                        || payment.getAmount().compareTo(amount) != 0 || !payment.getCurrency().equals(currency)
                        || payment.getUserId() != null && !payment.getUserId().equals(user)) {
                    throw new IllegalArgumentException("Idempotency key/order is bound to a different payment request");
                }
                if (payment.getStatus() == PaymentStatus.REFUNDED
                        || payment.getRefundedAmount() != null && payment.getRefundedAmount().signum() > 0) {
                    throw new PaymentDeclinedException("Payment has already been refunded");
                }
                if (payment.getStatus() == PaymentStatus.COMPLETED) {
                    return new ChargeAttempt(payment, null, completed(payment), false);
                }
            }
            var cached = keys.findByIdempotencyKey(key);
            if (cached.isPresent()) {
                if (!cached.get().getOrderId().equals(order)) {
                    throw new IllegalArgumentException("Idempotency key is bound to a different order");
                }
                PaymentResult result = mapper.readValue(cached.get().getResponse(), PaymentResult.class);
                if (!result.success()) throw new PaymentDeclinedException(result.message());
                return new ChargeAttempt(payment, null, result, false);
            }
            Instant now = databaseNow();
            if (payment != null && active(payment.getProcessingToken(), payment.getProcessingUntil(), now)) {
                return new ChargeAttempt(null, null, null, true);
            }
            if (payment == null) {
                payment = new Payment();
                payment.setOrderId(order);
                payment.setUserId(user);
                payment.setAmount(amount);
                payment.setCurrency(currency);
                payment.setIdempotencyKey(key);
            }
            UUID token = UUID.randomUUID();
            payment.setStatus(PaymentStatus.PROCESSING);
            payment.setProcessingToken(token);
            payment.setProcessingUntil(now.plusSeconds(60));
            return new ChargeAttempt(payments.saveAndFlush(payment), token, null, false);
        });
    }

    public PaymentResult finishCharge(ChargeAttempt attempt, GatewayResult result, RuntimeException failure) {
        return inTransaction(() -> {
            Payment original = attempt.payment();
            lock(original.getOrderId(), original.getIdempotencyKey());
            Payment payment = payments.findById(original.getId()).orElseThrow();
            if (payment.getStatus() == PaymentStatus.COMPLETED) return completed(payment);
            if (!attempt.token().equals(payment.getProcessingToken())) {
                throw new PaymentTransientException("Payment completion lease changed; retry the original key");
            }
            payment.setProcessingToken(null);
            payment.setProcessingUntil(null);
            payment.setStatus(result == null ? PaymentStatus.FAILED : PaymentStatus.COMPLETED);
            if (result != null) payment.setTransactionId(result.transactionId());
            payments.save(payment);
            PaymentResult response = result == null
                    ? new PaymentResult(false, "Payment declined: " + failure.getMessage(), null, null) : completed(payment);
            if (result != null || failure instanceof PaymentDeclinedException) {
                IdempotencyKey cached = new IdempotencyKey();
                cached.setIdempotencyKey(payment.getIdempotencyKey());
                cached.setOrderId(payment.getOrderId());
                cached.setResponse(mapper.writeValueAsString(response));
                cached.setExpiresAt(databaseNow().plus(24, ChronoUnit.HOURS));
                keys.save(cached);
            }
            return response;
        });
    }

    public RefundAttempt prepareRefund(Long id, String order, BigDecimal amount, String currency, String key) {
        return inTransaction(() -> {
            Payment payment = lockedPayment(id, order);
            String actualCurrency = currency == null ? payment.getCurrency() : currency;
            if (!actualCurrency.equals(payment.getCurrency())) {
                throw new IllegalArgumentException("Refund currency must match the payment currency");
            }
            RefundIntent intent = refunds.findByPaymentIdAndIdempotencyKey(id, key).orElse(null);
            if (intent != null) {
                if (intent.getAmount().compareTo(amount) != 0 || !intent.getCurrency().equals(actualCurrency)) {
                    throw new IllegalArgumentException("Refund key is bound to a different refund request");
                }
                if ("COMPLETED".equals(intent.getStatus())) {
                    return new RefundAttempt(payment, intent, null, refunded(payment, intent.getTransactionId()), false);
                }
            }
            if (payment.getStatus() == PaymentStatus.REFUNDED && "legacy".equals(key) && intent == null) {
                return new RefundAttempt(payment, null, null, refunded(payment, payment.getRefundTransactionId()), false);
            }
            if (payment.getStatus() != PaymentStatus.COMPLETED) {
                throw new IllegalStateException("Cannot refund payment: expected COMPLETED");
            }
            if ("legacy".equals(key) && intent == null && amount.compareTo(payment.getAmount()) != 0) {
                throw new IllegalArgumentException("Partial refunds require an idempotency key");
            }
            BigDecimal refunded = payment.getRefundedAmount() == null ? BigDecimal.ZERO : payment.getRefundedAmount();
            if (amount.compareTo(payment.getAmount().subtract(refunded)) > 0) {
                throw new IllegalArgumentException("Refund amount exceeds the remaining payment amount");
            }
            Instant now = databaseNow();
            if (refunds.existsByPaymentIdAndStatusAndIdempotencyKeyNot(id, "PENDING", key)
                    || intent != null && active(intent.getProcessingToken(), intent.getProcessingUntil(), now)) {
                return new RefundAttempt(null, null, null, null, true);
            }
            if (intent == null) {
                intent = new RefundIntent();
                intent.setPaymentId(id);
                intent.setIdempotencyKey(key);
                intent.setAmount(amount);
                intent.setCurrency(actualCurrency);
            }
            UUID token = UUID.randomUUID();
            intent.setStatus("PENDING");
            intent.setProcessingToken(token);
            intent.setProcessingUntil(now.plusSeconds(60));
            return new RefundAttempt(payment, refunds.saveAndFlush(intent), token, null, false);
        });
    }

    public PaymentResult finishRefund(RefundAttempt attempt, GatewayResult result, RuntimeException failure) {
        return inTransaction(() -> {
            Payment payment = lockedPayment(attempt.payment().getId(), attempt.payment().getOrderId());
            RefundIntent intent = refunds.findById(attempt.intent().getId()).orElseThrow();
            if ("COMPLETED".equals(intent.getStatus())) return refunded(payment, intent.getTransactionId());
            if (!attempt.token().equals(intent.getProcessingToken())) {
                throw new PaymentTransientException("Refund completion lease changed; retry the original key");
            }
            intent.setProcessingToken(null);
            intent.setProcessingUntil(null);
            if (result == null) {
                // Transport uncertainty keeps the amount reserved for this exact request until recovered.
                intent.setStatus(failure instanceof PaymentDeclinedException ? "FAILED" : "PENDING");
                refunds.save(intent);
                return null;
            }
            BigDecimal total = payment.getRefundedAmount().add(intent.getAmount());
            if (total.compareTo(payment.getAmount()) > 0) {
                throw new IllegalStateException("Refund receipts exceed captured amount; manual review required");
            }
            payment.setRefundedAmount(total);
            payment.setStatus(total.compareTo(payment.getAmount()) == 0 ? PaymentStatus.REFUNDED : PaymentStatus.COMPLETED);
            payment.setRefundTransactionId(result.transactionId());
            payments.save(payment);
            intent.setStatus("COMPLETED");
            intent.setTransactionId(result.transactionId());
            refunds.save(intent);
            return refunded(payment, result.transactionId());
        });
    }

    private Payment lockedPayment(Long id, String order) {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        payments.lockPaymentRequest("payment:order:" + order);
        Payment payment = payments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment", id));
        if (!payment.getOrderId().equals(order)) throw new IllegalArgumentException("Payment does not belong to order");
        payments.lockPaymentRequest("payment:key:" + payment.getIdempotencyKey());
        return payment;
    }

    private void lock(String order, String key) {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        payments.lockPaymentRequest("payment:order:" + order);
        payments.lockPaymentRequest("payment:key:" + key);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    private boolean active(UUID token, Instant until, Instant now) {
        return token != null && until != null && until.isAfter(now);
    }

    private <T> T inTransaction(Supplier<T> action) {
        try {
            return transactions.execute(status -> action.get());
        } catch (TransientDataAccessException contention) {
            throw new PaymentTransientException("Payment database temporarily unavailable; retry the original key");
        }
    }

    private PaymentResult completed(Payment payment) {
        return new PaymentResult(true, "Payment processed successfully", payment.getId().toString(), payment.getTransactionId());
    }

    private PaymentResult refunded(Payment payment, String receipt) {
        return new PaymentResult(true, "Payment refunded successfully", payment.getId().toString(), receipt == null ? "" : receipt);
    }

    public record ChargeAttempt(Payment payment, UUID token, PaymentResult result, boolean busy) { }
    public record RefundAttempt(Payment payment, RefundIntent intent, UUID token, PaymentResult result, boolean busy) { }
}
