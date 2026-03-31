package com.robomart.payment.integration;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.robomart.payment.entity.IdempotencyKey;
import com.robomart.payment.entity.OutboxEvent;
import com.robomart.payment.entity.Payment;
import com.robomart.payment.enums.PaymentStatus;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.repository.IdempotencyKeyRepository;
import com.robomart.payment.repository.OutboxEventRepository;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.service.MockPaymentGateway;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.service.PaymentService.PaymentResult;
import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class PaymentServiceIT {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private MockPaymentGateway mockPaymentGateway;

    @BeforeEach
    void setUp() {
        mockPaymentGateway.reset();
        outboxEventRepository.deleteAll();
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

        // Assert: outbox event created
        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo("payment_processed");
        assertThat(events.getFirst().getAggregateType()).isEqualTo("Payment");
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

        outboxEventRepository.deleteAll(); // Clear process events

        // Act: refund
        PaymentResult refundResult = paymentService.refundPayment(
                paymentId, "order-it-3", new BigDecimal("75.00"), "Customer request");

        // Assert: refund result
        assertThat(refundResult.success()).isTrue();
        assertThat(refundResult.transactionId()).startsWith("refund-");

        // Assert: payment status updated to REFUNDED
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // Assert: refund outbox event created
        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo("payment_refunded");
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
