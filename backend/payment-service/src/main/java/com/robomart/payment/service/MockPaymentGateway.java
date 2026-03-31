package com.robomart.payment.service;

import java.math.BigDecimal;
import java.util.UUID;

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

    public GatewayResult processPayment(BigDecimal amount, String currency) {
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
