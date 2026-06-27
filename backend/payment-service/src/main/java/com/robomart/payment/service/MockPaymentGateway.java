package com.robomart.payment.service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Charges already settled, keyed by idempotency key. Models a real gateway's idempotency-key
     * support (Stripe/Adyen-style): a repeated charge with the same key returns the original
     * transaction instead of charging again. This is what prevents double-charge when the caller
     * retries after a crash, or when two requests race concurrently with the same key.
     */
    private final ConcurrentHashMap<String, GatewayResult> chargesByKey = new ConcurrentHashMap<>();

    /**
     * Idempotent charge. Multiple calls with the same {@code idempotencyKey} charge the gateway
     * exactly once and return the same transaction. Failures are NOT cached, so a transient failure
     * may be retried.
     */
    public GatewayResult processPayment(BigDecimal amount, String currency, String idempotencyKey) {
        GatewayResult existing = chargesByKey.get(idempotencyKey);
        if (existing != null) {
            log.info("Idempotent charge replay for key={} — returning existing transactionId={}",
                    idempotencyKey, existing.transactionId());
            return existing;
        }
        // computeIfAbsent runs the charge at most once per key even under concurrency; if the
        // mapping function throws (transient/declined) no entry is stored, allowing a later retry.
        return chargesByKey.computeIfAbsent(idempotencyKey, key -> charge(amount, currency));
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
        this.chargesByKey.clear();
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
