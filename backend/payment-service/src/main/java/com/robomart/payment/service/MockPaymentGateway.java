package com.robomart.payment.service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.transaction.TransactionException;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.dao.TransientDataAccessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;

@Service
public class MockPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private volatile boolean simulateTransientFailure = false;
    private volatile boolean simulatePermanentFailure = false;
    private volatile long simulatedDelayMs = 0;

    private final MockGatewayLedger ledger;
    private final PaymentProviderCalls providerCalls;

    public MockPaymentGateway(MockGatewayLedger ledger, PaymentProviderCalls providerCalls) {
        this.ledger = ledger;
        this.providerCalls = providerCalls;
    }

    /** Replays durable settlement even after restart or an ambiguous caller transaction rollback. */
    public GatewayResult processPayment(BigDecimal amount, String currency, String idempotencyKey,
            String orderId, String userId) {
        String identity = orderId.length() + ":" + orderId + userId.length() + ":" + userId;
        return withProviderSlot(() -> ledger.settle("charge:" + idempotencyKey, amount, currency, identity, orderId,
                () -> charge(amount, currency)));
    }

    public GatewayResult processPayment(BigDecimal amount, String currency, String idempotencyKey) {
        return processPayment(amount, currency, idempotencyKey, "", "");
    }

    /** @deprecated use {@link #processPayment(BigDecimal, String, String)} so charges are idempotent. */
    @Deprecated
    public GatewayResult processPayment(BigDecimal amount, String currency) {
        return charge(amount, currency);
    }

    private GatewayResult charge(BigDecimal amount, String currency) {
        log.info("Processing payment: amount={}, currency={}", amount, currency);

        applyDelay();

        if (simulateTransientFailure) {
            log.warn("Simulated transient failure for payment: amount={}", amount);
            throw new PaymentTransientException("Payment gateway temporarily unavailable");
        }

        if (simulatePermanentFailure) {
            log.warn("Simulated permanent failure (declined) for payment: amount={}", amount);
            throw new PaymentDeclinedException("Payment declined by gateway");
        }

        String transactionId = "txn-" + UUID.randomUUID();
        log.info("Payment processed successfully: transactionId={}", transactionId);
        return new GatewayResult(transactionId, "COMPLETED");
    }

    public GatewayResult refundPayment(String transactionId, BigDecimal amount) {
        return withProviderSlot(() -> ledger.settle("refund:" + transactionId, amount, "", transactionId,
                () -> refund(transactionId, amount)));
    }

    public GatewayResult refundPayment(String transactionId, BigDecimal amount, String requestKey) {
        if ("legacy".equals(requestKey)) return refundPayment(transactionId, amount);
        return withProviderSlot(() -> ledger.settle("refund:" + transactionId + ":" + requestKey, amount, "", transactionId,
                () -> refund(transactionId, amount)));
    }

    private GatewayResult withProviderSlot(Supplier<GatewayResult> request) {
        try {
            return providerCalls.execute(request);
        } catch (InvocationRejectedException busy) {
            throw new PaymentTransientException("Payment provider is busy");
        } catch (TransactionException | TransientDataAccessException unavailable) {
            throw new PaymentTransientException("Payment provider temporarily unavailable");
        }
    }

    private GatewayResult refund(String transactionId, BigDecimal amount) {
        log.info("Processing refund: transactionId={}, amount={}", transactionId, amount);

        applyDelay();

        if (simulateTransientFailure) {
            log.warn("Simulated transient failure for refund: transactionId={}", transactionId);
            throw new PaymentTransientException("Payment gateway temporarily unavailable for refund");
        }

        if (simulatePermanentFailure) {
            log.warn("Simulated permanent failure for refund: transactionId={}", transactionId);
            throw new PaymentDeclinedException("Refund declined by gateway");
        }

        String refundTransactionId = "refund-" + UUID.randomUUID();
        log.info("Refund processed successfully: refundTransactionId={}", refundTransactionId);
        return new GatewayResult(refundTransactionId, "REFUNDED");
    }

    public void setSimulateTransientFailure(boolean simulate) {
        this.simulateTransientFailure = simulate;
    }

    public void setSimulatePermanentFailure(boolean simulate) {
        this.simulatePermanentFailure = simulate;
    }

    public void setSimulatedDelayMs(long delayMs) {
        this.simulatedDelayMs = delayMs;
    }

    public void reset() {
        this.simulateTransientFailure = false;
        this.simulatePermanentFailure = false;
        this.simulatedDelayMs = 0;
    }

    private void applyDelay() {
        if (simulatedDelayMs > 0) {
            try {
                Thread.sleep(simulatedDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PaymentTransientException("Payment processing interrupted");
            }
        }
    }
}
