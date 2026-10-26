package com.robomart.payment.integration;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.robomart.payment.entity.IdempotencyKey;
import com.robomart.payment.entity.Payment;
import com.robomart.payment.enums.PaymentStatus;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.repository.IdempotencyKeyRepository;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.service.MockPaymentGateway;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.service.PaymentService.PaymentResult;
import com.robomart.test.PostgresContainerConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
class PaymentServiceIT {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;


    @Autowired
    private MockPaymentGateway mockPaymentGateway;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockPaymentGateway.reset();
        jdbc.update("DELETE FROM mock_gateway_operations");
        jdbc.update("DELETE FROM refund_intents");
        jdbc.update("DELETE FROM retired_outbox_events");
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void shouldProcessPaymentAndCreateDbRecordsEndToEnd() {
        // Act
        PaymentResult result = paymentService.processPayment(
                "order-it-1", "user-1", new BigDecimal("49.99"), "USD", "idem-key-1");

        // Assert: result
        assertThat(result.success()).isTrue();
        assertThat(result.paymentId()).isNotEmpty();
        assertThat(result.transactionId()).startsWith("txn-");

        // Assert: payment record in DB
        Payment payment = paymentRepository.findByIdempotencyKey("idem-key-1").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getOrderId()).isEqualTo("order-it-1");
        assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("49.99"));
        assertThat(payment.getTransactionId()).isNotEmpty();

        // Assert: idempotency key stored
        IdempotencyKey idempotencyKey = idempotencyKeyRepository.findByIdempotencyKey("idem-key-1").orElseThrow();
        assertThat(idempotencyKey.getOrderId()).isEqualTo("order-it-1");
        assertThat(idempotencyKey.getResponse()).isNotEmpty();
        assertThat(idempotencyKey.getExpiresAt()).isAfter(java.time.Instant.now());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM retired_outbox_events", Integer.class)).isZero();

    }

    @Test
    void shouldReturnCachedResponseForDuplicateIdempotencyKey() {
        // Arrange: process first payment
        PaymentResult firstResult = paymentService.processPayment(
                "order-it-2", "user-1", new BigDecimal("29.99"), "USD", "idem-key-dup");

        // Act: process duplicate with same idempotency key
        PaymentResult secondResult = paymentService.processPayment(
                "order-it-2", "user-1", new BigDecimal("29.99"), "USD", "idem-key-dup");

        // Assert: same result returned
        assertThat(secondResult.success()).isEqualTo(firstResult.success());
        assertThat(secondResult.paymentId()).isEqualTo(firstResult.paymentId());
        assertThat(secondResult.transactionId()).isEqualTo(firstResult.transactionId());

        // Assert: only ONE payment record (no duplicate)
        List<Payment> payments = paymentRepository.findAll();
        long paymentCount = payments.stream()
                .filter(p -> "order-it-2".equals(p.getOrderId()))
                .count();
        assertThat(paymentCount).isEqualTo(1);
    }

    @Test
    void shouldRefundCompletedPaymentEndToEnd() {
        // Arrange: create a completed payment
        PaymentResult processResult = paymentService.processPayment(
                "order-it-3", "user-1", new BigDecimal("75.00"), "USD", "idem-key-refund");
        Long paymentId = Long.parseLong(processResult.paymentId());

        // Financial receipts are recorded in payment/provider ledgers.

        // Act: refund
        PaymentResult refundResult = paymentService.refundPayment(
                paymentId, "order-it-3", new BigDecimal("75.00"), "Customer request");

        // Assert: refund result
        assertThat(refundResult.success()).isTrue();
        assertThat(refundResult.transactionId()).startsWith("refund-");

        // Assert: payment status updated to REFUNDED
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("75.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_intents WHERE status='COMPLETED'", Integer.class))
                .isEqualTo(1);

    }

    @Test
    void shouldSetFailedStatusOnTransientFailure() {
        // Arrange
        mockPaymentGateway.setSimulateTransientFailure(true);

        // Act & Assert
        assertThatThrownBy(() -> paymentService.processPayment(
                "order-it-4", "user-1", new BigDecimal("10.00"), "USD", "idem-key-transient"))
                .isInstanceOf(PaymentTransientException.class);

        // Assert: payment saved as FAILED
        Payment payment = paymentRepository.findByIdempotencyKey("idem-key-transient").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        // Assert: NO idempotency key stored (allow retry)
        assertThat(idempotencyKeyRepository.findByIdempotencyKey("idem-key-transient")).isEmpty();
    }

    @Test
    void shouldAllowRetryAfterTransientFailure() {
        // Arrange: first attempt fails
        mockPaymentGateway.setSimulateTransientFailure(true);
        assertThatThrownBy(() -> paymentService.processPayment(
                "order-it-5", "user-1", new BigDecimal("10.00"), "USD", "idem-key-retry"))
                .isInstanceOf(PaymentTransientException.class);

        // Act: second attempt with same key succeeds (gateway recovers)
        // Fix #1: service reuses existing FAILED payment — no manual delete needed
        mockPaymentGateway.reset();
        jdbc.update("DELETE FROM mock_gateway_operations");

        PaymentResult result = paymentService.processPayment(
                "order-it-5", "user-1", new BigDecimal("10.00"), "USD", "idem-key-retry");

        // Assert
        assertThat(result.success()).isTrue();
    }

    @Test
    void shouldStoreIdempotencyKeyOnPermanentFailure() {
        // Arrange
        mockPaymentGateway.setSimulatePermanentFailure(true);

        // Act & Assert
        assertThatThrownBy(() -> paymentService.processPayment(
                "order-it-6", "user-1", new BigDecimal("10.00"), "USD", "idem-key-declined"))
                .isInstanceOf(PaymentDeclinedException.class);

        // Assert: idempotency key IS stored (prevent re-attempt)
        assertThat(idempotencyKeyRepository.findByIdempotencyKey("idem-key-declined")).isPresent();
    }
}
