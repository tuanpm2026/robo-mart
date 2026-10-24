package com.robomart.payment.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import com.robomart.payment.service.GatewayResult;
import com.robomart.payment.service.MockPaymentGateway;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.service.PaymentService.PaymentResult;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Unit Tests")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private MockPaymentGateway paymentGateway;

    @Captor
    private ArgumentCaptor<Payment> paymentCaptor;

    @Captor
    private ArgumentCaptor<IdempotencyKey> idempotencyKeyCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private PaymentService paymentService;
    private ObjectMapper objectMapper;

    // Helper to create a TransactionTemplate that executes synchronously
    private TransactionTemplate createSynchronousTransactionTemplate() {
        TransactionTemplate tt = new TransactionTemplate();
        // Use a mock PlatformTransactionManager that executes immediately
        tt.setTransactionManager(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() { return new Object(); }
            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
            @Override
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            @Override
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
        });
        return tt;
    }

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        TransactionTemplate transactionTemplate = createSynchronousTransactionTemplate();
        paymentService = new PaymentService(
                paymentRepository,
                idempotencyKeyRepository,
                outboxEventRepository,
                paymentGateway,
                transactionTemplate,
                objectMapper
        );
    }

    private Payment createSavedPayment(Long id, String orderId, PaymentStatus status, String txnId) {
        Payment payment = new Payment();
        // Use reflection to set the id since there's no setter
        try {
            var idField = Payment.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(payment, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        payment.setOrderId(orderId);
        payment.setAmount(new BigDecimal("99.99"));
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setTransactionId(txnId);
        payment.setIdempotencyKey("key-" + orderId);
        return payment;
    }

    @Nested
    @DisplayName("processPayment")
    class ProcessPaymentTests {

        @Test
        @DisplayName("should process new payment successfully")
        void shouldProcessNewPaymentSuccessfully() {
            // given
            when(idempotencyKeyRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
            when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
            when(paymentGateway.processPayment(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(new GatewayResult("txn-abc", "COMPLETED"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
                Payment p = inv.getArgument(0);
                try {
                    var idField = Payment.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(p, 1L);
                } catch (Exception e) { throw new RuntimeException(e); }
                return p;
            });
            lenient().when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // when
            PaymentResult result = paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("99.99"), "USD", "key-1");

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.transactionId()).isEqualTo("txn-abc");
            assertThat(result.paymentId()).isEqualTo("1");

            verify(paymentRepository).save(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();
            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(savedPayment.getTransactionId()).isEqualTo("txn-abc");

            verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));
            verify(outboxEventRepository).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("should return cached response for duplicate idempotency key")
        void shouldReturnCachedResponseWhenDuplicateKey() {
            // given
            PaymentResult cachedResult = new PaymentResult(true, "Payment processed successfully", "1", "txn-abc");
            String cachedJson;
            try {
                cachedJson = objectMapper.writeValueAsString(cachedResult);
            } catch (Exception e) { throw new RuntimeException(e); }

            IdempotencyKey existingKey = new IdempotencyKey();
            existingKey.setIdempotencyKey("key-dup");
            existingKey.setOrderId("order-1");
            existingKey.setResponse(cachedJson);
            existingKey.setExpiresAt(Instant.now().plus(12, ChronoUnit.HOURS));

            when(idempotencyKeyRepository.findByIdempotencyKey("key-dup")).thenReturn(Optional.of(existingKey));

            // when
            PaymentResult result = paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("99.99"), "USD", "key-dup");

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.paymentId()).isEqualTo("1");
            assertThat(result.transactionId()).isEqualTo("txn-abc");

            // Verify no gateway call and no new saves
            org.mockito.Mockito.verifyNoInteractions(paymentGateway);
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should set FAILED status and NOT store idempotency key on transient failure")
        void shouldFailWithoutIdempotencyKeyOnTransientFailure() {
            // given
            when(idempotencyKeyRepository.findByIdempotencyKey("key-transient")).thenReturn(Optional.empty());
            when(paymentRepository.findByIdempotencyKey("key-transient")).thenReturn(Optional.empty());
            when(paymentGateway.processPayment(any(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new PaymentTransientException("Temporary error"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

            // when / then
            assertThatThrownBy(() -> paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("99.99"), "USD", "key-transient"))
                    .isInstanceOf(PaymentTransientException.class);

            verify(paymentRepository).save(paymentCaptor.capture());
            assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);

            // Idempotency key should NOT be stored (allow retry)
            verify(idempotencyKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("should set FAILED status and store idempotency key on permanent failure")
        void shouldFailWithIdempotencyKeyOnPermanentFailure() {
            // given
            when(idempotencyKeyRepository.findByIdempotencyKey("key-declined")).thenReturn(Optional.empty());
            when(paymentRepository.findByIdempotencyKey("key-declined")).thenReturn(Optional.empty());
            when(paymentGateway.processPayment(any(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new PaymentDeclinedException("Card declined"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // when / then
            assertThatThrownBy(() -> paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("99.99"), "USD", "key-declined"))
                    .isInstanceOf(PaymentDeclinedException.class);

            verify(paymentRepository).save(paymentCaptor.capture());
            assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);

            // Idempotency key SHOULD be stored (prevent re-attempt)
            verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));
        }

        @Test
        @DisplayName("should replay the durable completed payment after cache expiry without charging")
        void shouldReplayCompletedPaymentWhenCacheExpired() {
            Payment completed = createSavedPayment(2L, "order-1", PaymentStatus.COMPLETED, "txn-original");
            completed.setIdempotencyKey("key-expired");
            when(paymentRepository.findByIdempotencyKey("key-expired")).thenReturn(Optional.of(completed));
            PaymentResult result = paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("99.99"), "USD", "key-expired");
            assertThat(result.paymentId()).isEqualTo("2");
            assertThat(result.transactionId()).isEqualTo("txn-original");
            org.mockito.Mockito.verifyNoInteractions(paymentGateway, idempotencyKeyRepository, outboxEventRepository);
            verify(paymentRepository, never()).delete(any());
        }

        @Test
        @DisplayName("[Fix #4] should reject null amount")
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> paymentService.processPayment("order-1", "user-1",
                    null, "USD", "key-v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("[Fix #4] should reject zero amount")
        void shouldRejectZeroAmount() {
            assertThatThrownBy(() -> paymentService.processPayment("order-1", "user-1",
                    BigDecimal.ZERO, "USD", "key-v2"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("[Fix #4] should reject blank currency")
        void shouldRejectBlankCurrency() {
            assertThatThrownBy(() -> paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("10.00"), "  ", "key-v3"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Currency");
        }

        @Test
        @DisplayName("[Fix #1] should reuse existing FAILED payment on retry")
        void shouldReuseExistingFailedPaymentOnRetry() {
            // given — FAILED payment exists from previous transient failure
            Payment failedPayment = createSavedPayment(5L, "order-retry", PaymentStatus.FAILED, null);
            failedPayment.setIdempotencyKey("key-retry");

            when(idempotencyKeyRepository.findByIdempotencyKey("key-retry")).thenReturn(Optional.empty());
            when(paymentRepository.findByIdempotencyKey("key-retry")).thenReturn(Optional.of(failedPayment));
            when(paymentGateway.processPayment(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(new GatewayResult("txn-retry", "COMPLETED"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // when
            PaymentResult result = paymentService.processPayment("order-retry", "user-1",
                    new BigDecimal("99.99"), "USD", "key-retry");

            // then — reused same payment record (id=5)
            assertThat(result.success()).isTrue();
            assertThat(result.paymentId()).isEqualTo("5");
            assertThat(result.transactionId()).isEqualTo("txn-retry");

            verify(paymentRepository).save(paymentCaptor.capture());
            assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        }

        @Test
        void shouldRejectRequestsThatCannotFitTheLedgerBeforeProviderSettlement() {
            assertThatThrownBy(() -> paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("99.99"), "USD", "k".repeat(101)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> paymentService.processPayment("order-1", "user-1",
                    new BigDecimal("0.001"), "USD", "key-fraction"))
                    .isInstanceOf(IllegalArgumentException.class);
            org.mockito.Mockito.verifyNoInteractions(paymentGateway, paymentRepository, idempotencyKeyRepository);
        }

        @Test
        void shouldLockOrderAndKeyBeforeCallingGateway() {
            when(paymentGateway.processPayment(any(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new GatewayResult("txn-locked", "COMPLETED"));
            when(paymentRepository.save(any())).thenReturn(
                    createSavedPayment(3L, "order-lock", PaymentStatus.COMPLETED, "txn-locked"));
            paymentService.processPayment("order-lock", "user-1", new BigDecimal("99.99"), "USD", "key-lock");
            var ordered = org.mockito.Mockito.inOrder(paymentRepository, paymentGateway);
            ordered.verify(paymentRepository).lockPaymentRequest("payment:order:order-lock");
            ordered.verify(paymentRepository).lockPaymentRequest("payment:key:key-lock");
            ordered.verify(paymentGateway).processPayment(new BigDecimal("99.99"), "USD", "key-lock", "order-lock", "user-1");
        }

        @Test
        void shouldRejectDifferentAmountBeforeReplayingOrCharging() {
            Payment completed = createSavedPayment(4L, "order-conflict", PaymentStatus.COMPLETED, "txn-original");
            when(paymentRepository.findByIdempotencyKey("key-order-conflict")).thenReturn(Optional.of(completed));
            assertThatThrownBy(() -> paymentService.processPayment("order-conflict", "user-1",
                    new BigDecimal("100.00"), "USD", "key-order-conflict"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different payment request");
            org.mockito.Mockito.verifyNoInteractions(paymentGateway);
        }

        @Test
        void shouldRejectDifferentUserBeforeReplayingOrCharging() {
            Payment completed = createSavedPayment(4L, "order-conflict", PaymentStatus.COMPLETED, "txn-original");
            completed.setUserId("owner");
            when(paymentRepository.findByIdempotencyKey("key-order-conflict")).thenReturn(Optional.of(completed));
            assertThatThrownBy(() -> paymentService.processPayment("order-conflict", "another-user",
                    new BigDecimal("99.99"), "USD", "key-order-conflict"))
                    .isInstanceOf(IllegalArgumentException.class);
            org.mockito.Mockito.verifyNoInteractions(paymentGateway);
        }

        @Test
        void shouldNeverRechargeRefundedPayment() {
            Payment refunded = createSavedPayment(4L, "order-refunded", PaymentStatus.REFUNDED, "txn-original");
            when(paymentRepository.findByIdempotencyKey("key-order-refunded")).thenReturn(Optional.of(refunded));
            assertThatThrownBy(() -> paymentService.processPayment("order-refunded", "user-1",
                    new BigDecimal("99.99"), "USD", "key-order-refunded"))
                    .isInstanceOf(PaymentDeclinedException.class).hasMessageContaining("refunded");
            org.mockito.Mockito.verifyNoInteractions(paymentGateway);
        }

        @Test
        void shouldReplayPermanentDeclineEvenWhenCacheExpired() {
            IdempotencyKey cached = new IdempotencyKey();
            cached.setOrderId("order-declined");
            cached.setResponse(objectMapper.writeValueAsString(new PaymentResult(false, "Card declined", null, null)));
            cached.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
            when(idempotencyKeyRepository.findByIdempotencyKey("key-declined")).thenReturn(Optional.of(cached));
            assertThatThrownBy(() -> paymentService.processPayment("order-declined", "user-1",
                    new BigDecimal("99.99"), "USD", "key-declined"))
                    .isInstanceOf(PaymentDeclinedException.class);
            org.mockito.Mockito.verifyNoInteractions(paymentGateway);
        }
    }

    @Nested
    @DisplayName("refundPayment")
    class RefundPaymentTests {

        @Test
        void shouldRejectRefundAmountsThatCannotFitTheLedgerBeforeProviderSettlement() {
            for (String amount : java.util.List.of("0.001", "100000000000000000.00")) {
                assertThatThrownBy(() -> paymentService.refundPayment(1L, "order-1",
                        new BigDecimal(amount), "invalid precision"))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 decimal places");
            }
            org.mockito.Mockito.verifyNoInteractions(paymentGateway, paymentRepository, outboxEventRepository);
        }

        @Test
        @DisplayName("should refund completed payment successfully")
        void shouldRefundCompletedPaymentSuccessfully() {
            // given
            Payment payment = createSavedPayment(1L, "order-1", PaymentStatus.COMPLETED, "txn-abc");
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            when(paymentGateway.refundPayment(eq("txn-abc"), any())).thenReturn(new GatewayResult("refund-xyz", "REFUNDED"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // when
            PaymentResult result = paymentService.refundPayment(1L, "order-1", new BigDecimal("99.99"), "Customer request");

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.transactionId()).isEqualTo("refund-xyz");

            verify(paymentRepository).save(paymentCaptor.capture());
            assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.REFUNDED);

            verify(outboxEventRepository).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when payment not found")
        void shouldThrowNotFoundWhenPaymentNotExists() {
            // given
            when(paymentRepository.findById(999L)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> paymentService.refundPayment(999L, "order-1", new BigDecimal("99.99"), "test"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should throw IllegalStateException when refunding non-COMPLETED payment")
        void shouldThrowIllegalStateWhenPaymentNotCompleted() {
            // given
            Payment payment = createSavedPayment(1L, "order-1", PaymentStatus.FAILED, "txn-abc");
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            // when / then
            assertThatThrownBy(() -> paymentService.refundPayment(1L, "order-1", new BigDecimal("99.99"), "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAILED")
                    .hasMessageContaining("COMPLETED");
        }

        @Test
        @DisplayName("[Fix #5] should reject refund amount exceeding payment amount")
        void shouldRejectRefundExceedingPaymentAmount() {
            // given — completed payment for 50.00
            Payment payment = createSavedPayment(1L, "order-1", PaymentStatus.COMPLETED, "txn-abc");
            payment.setAmount(new BigDecimal("50.00"));
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            // when / then — refund 100.00 > 50.00
            assertThatThrownBy(() -> paymentService.refundPayment(1L, "order-1", new BigDecimal("100.00"), "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds");
        }
    }
}
