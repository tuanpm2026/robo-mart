package com.robomart.payment.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.service.GatewayResult;
import com.robomart.payment.service.MockPaymentGateway;
import com.robomart.payment.service.PaymentProviderCalls;
import com.robomart.payment.service.MockGatewayLedger;

@DisplayName("MockPaymentGateway Unit Tests")
class MockPaymentGatewayTest {

    private MockPaymentGateway gateway;
    private MockGatewayLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = mock(MockGatewayLedger.class);
        when(ledger.settle(anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(call -> ((Supplier<GatewayResult>) call.getArgument(5)).get());
        when(ledger.settle(anyString(), any(), anyString(), anyString(), any()))
                .thenAnswer(call -> ((Supplier<GatewayResult>) call.getArgument(4)).get());
        gateway = new MockPaymentGateway(ledger, new PaymentProviderCalls());
    }

    @Test
    void shouldMapFrameworkConcurrencyRejectionToTransientProviderFailure() {
        var calls = mock(PaymentProviderCalls.class);
        when(calls.execute(any())).thenThrow(new org.springframework.resilience.InvocationRejectedException("busy", calls));
        var limited = new MockPaymentGateway(ledger, calls);
        assertThatThrownBy(() -> limited.processPayment(new BigDecimal("10.00"), "USD", "busy-key"))
                .isInstanceOf(PaymentTransientException.class).hasMessageContaining("busy");
    }

    @Nested
    @DisplayName("processPayment")
    class ProcessPayment {

        @Test
        @DisplayName("should return success with transaction ID when payment succeeds")
        void shouldReturnSuccessWhenPaymentSucceeds() {
            GatewayResult result = gateway.processPayment(new BigDecimal("99.99"), "USD");

            assertThat(result.transactionId()).startsWith("txn-");
            assertThat(result.status()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("should throw PaymentTransientException when transient failure simulated")
        void shouldThrowTransientExceptionWhenTransientFailure() {
            gateway.setSimulateTransientFailure(true);

            assertThatThrownBy(() -> gateway.processPayment(new BigDecimal("99.99"), "USD"))
                    .isInstanceOf(PaymentTransientException.class)
                    .hasMessageContaining("temporarily unavailable");
        }

        @Test
        @DisplayName("should throw PaymentDeclinedException when permanent failure simulated")
        void shouldThrowDeclinedExceptionWhenPermanentFailure() {
            gateway.setSimulatePermanentFailure(true);

            assertThatThrownBy(() -> gateway.processPayment(new BigDecimal("99.99"), "USD"))
                    .isInstanceOf(PaymentDeclinedException.class)
                    .hasMessageContaining("declined");
        }

        @Test
        @DisplayName("should generate unique transaction IDs")
        void shouldGenerateUniqueTransactionIds() {
            GatewayResult result1 = gateway.processPayment(new BigDecimal("10.00"), "USD");
            GatewayResult result2 = gateway.processPayment(new BigDecimal("20.00"), "USD");

            assertThat(result1.transactionId()).isNotEqualTo(result2.transactionId());
        }

        @Test
        @DisplayName("should charge once and return the same transaction for a repeated idempotency key")
        void shouldChargeOnceForSameIdempotencyKey() {
            GatewayResult settled = new GatewayResult("txn-durable", "COMPLETED");
            when(ledger.settle(eq("charge:key-1"), any(), eq("USD"), eq("0:0:"), eq(""), any())).thenReturn(settled);
            GatewayResult first = gateway.processPayment(new BigDecimal("99.99"), "USD", "key-1");
            GatewayResult replay = gateway.processPayment(new BigDecimal("99.99"), "USD", "key-1");

            // Same key → same transaction (charged once).
            assertThat(replay.transactionId()).isEqualTo(first.transactionId());

            // A different key charges independently.
            GatewayResult other = gateway.processPayment(new BigDecimal("99.99"), "USD", "key-2");
            assertThat(other.transactionId()).isNotEqualTo(first.transactionId());
            verify(ledger, org.mockito.Mockito.times(2))
                    .settle(eq("charge:key-1"), any(), eq("USD"), eq("0:0:"), eq(""), any());
        }

        @Test
        @DisplayName("should not cache a failed charge — a retry with the same key can succeed")
        void shouldNotCacheFailedChargeForKey() {
            gateway.setSimulateTransientFailure(true);
            assertThatThrownBy(() -> gateway.processPayment(new BigDecimal("99.99"), "USD", "key-retry"))
                    .isInstanceOf(PaymentTransientException.class);

            // Transient cleared — same key now charges successfully (failure was not cached).
            gateway.setSimulateTransientFailure(false);
            GatewayResult result = gateway.processPayment(new BigDecimal("99.99"), "USD", "key-retry");
            assertThat(result.transactionId()).startsWith("txn-");
        }
    }

    @Nested
    @DisplayName("refundPayment")
    class RefundPayment {

        @Test
        @DisplayName("should return success with refund transaction ID")
        void shouldReturnSuccessWhenRefundSucceeds() {
            GatewayResult result = gateway.refundPayment("txn-123", new BigDecimal("50.00"));

            assertThat(result.transactionId()).startsWith("refund-");
            assertThat(result.status()).isEqualTo("REFUNDED");
        }

        @Test
        @DisplayName("should throw PaymentTransientException on transient failure")
        void shouldThrowTransientExceptionOnRefundTransientFailure() {
            gateway.setSimulateTransientFailure(true);

            assertThatThrownBy(() -> gateway.refundPayment("txn-123", new BigDecimal("50.00")))
                    .isInstanceOf(PaymentTransientException.class);
        }

        @Test
        @DisplayName("should throw PaymentDeclinedException on permanent failure")
        void shouldThrowDeclinedExceptionOnRefundPermanentFailure() {
            gateway.setSimulatePermanentFailure(true);

            assertThatThrownBy(() -> gateway.refundPayment("txn-123", new BigDecimal("50.00")))
                    .isInstanceOf(PaymentDeclinedException.class);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("should clear all failure simulations after reset")
        void shouldClearAllFailureSimulationsAfterReset() {
            gateway.setSimulateTransientFailure(true);
            gateway.setSimulatePermanentFailure(true);
            gateway.setSimulatedDelayMs(1000);

            gateway.reset();

            GatewayResult result = gateway.processPayment(new BigDecimal("10.00"), "USD");
            assertThat(result.transactionId()).startsWith("txn-");
        }
    }
}
