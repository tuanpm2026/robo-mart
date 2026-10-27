package com.robomart.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.common.audit.AuditAction;
import com.robomart.common.audit.Auditable;
import com.robomart.payment.dto.ReconciliationSummaryResponse;
import com.robomart.payment.entity.Payment;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.service.PaymentIntentStore.ChargeAttempt;
import com.robomart.payment.service.PaymentIntentStore.RefundAttempt;

/** Provider calls never hold an application transaction, connection or advisory lock. */
@Service
public class PaymentService {
    private final PaymentIntentStore intents;
    private final MockPaymentGateway gateway;
    private final PaymentRepository payments;

    public PaymentService(PaymentIntentStore intents, MockPaymentGateway gateway, PaymentRepository payments) {
        this.intents = intents;
        this.gateway = gateway;
        this.payments = payments;
    }

    @Auditable(action = AuditAction.CREATE, entityType = "Payment", entityIdExpression = "#result?.paymentId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentResult processPayment(String order, String user, BigDecimal amount, String currency, String key) {
        validateAmount(amount, "Payment");
        validateIdentity(order, "Order");
        validateIdentity(user, "User");
        validateIdentity(key, "Idempotency key");
        validateCurrency(currency);
        ChargeAttempt attempt = waitForIntent(() -> intents.prepareCharge(order, user, amount, currency, key),
                ChargeAttempt::busy);
        if (attempt.result() != null) return attempt.result();
        GatewayResult result;
        try {
            result = gateway.processPayment(amount, currency, key, order, user);
        } catch (PaymentTransientException | PaymentDeclinedException failure) {
            intents.finishCharge(attempt, null, failure);
            throw failure;
        }
        return intents.finishCharge(attempt, result, null);
    }

    /** Compatibility entry for existing in-process callers; gRPC supplies an explicit currency. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.UPDATE, entityType = "PAYMENT", entityIdExpression = "#result?.paymentId")
    public PaymentResult refundPayment(Long id, String order, BigDecimal amount, String reason) {
        return refundPayment(id, order, amount, null, reason, "");
    }

    @Auditable(action = AuditAction.UPDATE, entityType = "PAYMENT", entityIdExpression = "#result?.paymentId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentResult refundPayment(Long id, String order, BigDecimal amount, String currency, String reason, String key) {
        validateAmount(amount, "Refund");
        validateIdentity(order, "Order");
        if (id == null || id <= 0) throw new IllegalArgumentException("Payment ID must be positive");
        if (currency != null) validateCurrency(currency);
        String requestKey = key == null || key.isBlank() ? "legacy" : key;
        validateIdentity(requestKey, "Refund idempotency key");
        RefundAttempt attempt = waitForIntent(() -> intents.prepareRefund(id, order, amount, currency, requestKey),
                RefundAttempt::busy);
        if (attempt.result() != null) return attempt.result();
        GatewayResult result;
        try {
            result = gateway.refundPayment(attempt.payment().getTransactionId(), amount, requestKey);
        } catch (PaymentTransientException | PaymentDeclinedException failure) {
            intents.finishRefund(attempt, null, failure);
            throw failure;
        }
        return intents.finishRefund(attempt, result, null);
    }

    private <T> T waitForIntent(Supplier<T> prepare, java.util.function.Predicate<T> busy) {
        // Wait only outside database transactions; duplicate callers can observe the first receipt.
        for (int attempt = 0; attempt < 40; attempt++) {
            T value = prepare.get();
            if (!busy.test(value)) return value;
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PaymentTransientException("Payment request interrupted; retry its original key");
            }
        }
        throw new PaymentTransientException("Payment operation is already in progress; retry its original key");
    }

    private void validateAmount(BigDecimal amount, String operation) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException(operation + " amount must be positive");
        if (amount.stripTrailingZeros().scale() > 2 || amount.precision() - amount.scale() > 17) {
            throw new IllegalArgumentException(operation + " amount must fit 19 digits with at most 2 decimal places");
        }
    }

    private void validateIdentity(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(name + " must contain 1 to 100 characters");
        }
    }

    private void validateCurrency(String currency) {
        if (currency == null || currency.isBlank() || currency.length() != 3) {
            throw new IllegalArgumentException("Currency must contain exactly 3 characters");
        }
    }

    public Optional<Payment> findByOrderId(String order) {
        return payments.findByOrderId(order);
    }

    public ReconciliationSummaryResponse getReconciliationSummary() {
        return getReconciliationSummary(0, 100);
    }

    public ReconciliationSummaryResponse getReconciliationSummary(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid reconciliation page");
        var result = payments.findAll(PageRequest.of(page, size));
        var content = result.getContent().stream().map(p -> new ReconciliationSummaryResponse.OrderPaymentSummary(
                p.getOrderId(), p.getStatus().name(), p.getAmount().toPlainString())).toList();
        return new ReconciliationSummaryResponse(content, Instant.now(), page, size, result.hasNext());
    }

    public record PaymentResult(boolean success, String message, String paymentId, String transactionId) { }
}
