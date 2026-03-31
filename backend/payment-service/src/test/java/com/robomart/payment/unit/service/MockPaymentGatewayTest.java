package com.robomart.payment.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.service.GatewayResult;
import com.robomart.payment.service.MockPaymentGateway;

@DisplayName("MockPaymentGateway Unit Tests")
class MockPaymentGatewayTest {

    private MockPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockPaymentGateway();
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
