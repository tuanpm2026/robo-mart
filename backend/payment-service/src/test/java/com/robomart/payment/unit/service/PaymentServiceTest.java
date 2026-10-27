package com.robomart.payment.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.payment.entity.IdempotencyKey;
import com.robomart.payment.entity.Payment;
import com.robomart.payment.entity.RefundIntent;
import com.robomart.payment.enums.PaymentStatus;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.repository.IdempotencyKeyRepository;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.repository.RefundIntentRepository;
import com.robomart.payment.service.GatewayResult;
import com.robomart.payment.service.MockPaymentGateway;
import com.robomart.payment.service.PaymentIntentStore;
import com.robomart.payment.service.PaymentService;

import tools.jackson.databind.json.JsonMapper;

/** Exercises business boundaries with real intent logic; persistence/concurrency is verified by ITs. */
class PaymentServiceTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final IdempotencyKeyRepository keys = mock(IdempotencyKeyRepository.class);
    private final RefundIntentRepository refunds = mock(RefundIntentRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MockPaymentGateway gateway = mock(MockPaymentGateway.class);
    private final Map<Long, Payment> paymentRows = new HashMap<>();
    private final Map<String, IdempotencyKey> cache = new HashMap<>();
    private final Map<Long, RefundIntent> refundRows = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Instant now = Instant.parse("2026-10-26T00:00:00Z");
    private PaymentIntentStore intents;
    private PaymentService service;

    @BeforeEach
    void setup() {
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        intents = new PaymentIntentStore(payments, keys, refunds, jdbc, JsonMapper.builder().build(),
                new TransactionTemplate(manager));
        service = new PaymentService(intents, gateway, payments);
        lenient().when(jdbc.queryForObject("SELECT now()", Timestamp.class)).thenReturn(Timestamp.from(now));
        lenient().when(payments.saveAndFlush(any())).thenAnswer(i -> save(i.getArgument(0)));
        lenient().when(payments.save(any())).thenAnswer(i -> save(i.getArgument(0)));
        lenient().when(payments.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(paymentRows.get(i.getArgument(0))));
        lenient().when(payments.findByOrderId(anyString())).thenAnswer(i -> paymentRows.values().stream()
                .filter(p -> p.getOrderId().equals(i.getArgument(0))).findFirst());
        lenient().when(payments.findByIdempotencyKey(anyString())).thenAnswer(i -> paymentRows.values().stream()
                .filter(p -> p.getIdempotencyKey().equals(i.getArgument(0))).findFirst());
        lenient().when(keys.findByIdempotencyKey(anyString())).thenAnswer(i -> Optional.ofNullable(cache.get(i.getArgument(0))));
        lenient().when(keys.save(any())).thenAnswer(i -> {
            IdempotencyKey key = i.getArgument(0); cache.put(key.getIdempotencyKey(), key); return key;
        });
        lenient().when(refunds.saveAndFlush(any())).thenAnswer(i -> saveRefund(i.getArgument(0)));
        lenient().when(refunds.save(any())).thenAnswer(i -> saveRefund(i.getArgument(0)));
        lenient().when(refunds.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(refundRows.get(i.getArgument(0))));
        lenient().when(refunds.findByPaymentIdAndIdempotencyKey(anyLong(), anyString())).thenAnswer(i ->
                refundRows.values().stream().filter(r -> r.getPaymentId().equals(i.getArgument(0))
                        && r.getIdempotencyKey().equals(i.getArgument(1))).findFirst());
        lenient().when(refunds.existsByPaymentIdAndStatusAndIdempotencyKeyNot(anyLong(), anyString(), anyString()))
                .thenAnswer(i -> refundRows.values().stream().anyMatch(r -> r.getPaymentId().equals(i.getArgument(0))
                        && r.getStatus().equals(i.getArgument(1)) && !r.getIdempotencyKey().equals(i.getArgument(2))));
        lenient().when(gateway.processPayment(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GatewayResult("charge-receipt", "COMPLETED"));
        lenient().when(gateway.refundPayment(anyString(), any(), anyString()))
                .thenAnswer(i -> new GatewayResult("refund-" + i.getArgument(2), "REFUNDED"));
    }

    private Payment save(Payment payment) {
        if (payment.getId() == null) ReflectionTestUtils.setField(payment, "id", ids.incrementAndGet());
        paymentRows.put(payment.getId(), payment);
        return payment;
    }

    private RefundIntent saveRefund(RefundIntent refund) {
        if (refund.getId() == null) ReflectionTestUtils.setField(refund, "id", ids.incrementAndGet());
        refundRows.put(refund.getId(), refund);
        return refund;
    }

    private Payment charge() {
        service.processPayment("order", "user", new BigDecimal("100.00"), "USD", "key");
        return paymentRows.values().iterator().next();
    }

    @Test
    void providerSeesCommittedIntentAndNoActiveApplicationTransaction() {
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(paymentRows.values().iterator().next().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            return new GatewayResult("receipt", "COMPLETED");
        }).when(gateway).processPayment(any(), anyString(), anyString(), anyString(), anyString());
        Payment payment = charge();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getProcessingToken()).isNull();
        assertThat(cache.get("key").getResponse()).contains("receipt");
    }

    @Test
    void settledPaymentReplaysEvenAfterResponseCacheIsLost() {
        Payment payment = charge();
        cache.clear();
        assertThat(service.processPayment("order", "user", new BigDecimal("100.00"), "USD", "key").paymentId())
                .isEqualTo(payment.getId().toString());
        verify(gateway).processPayment(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void requestBindingRejectsChangedUserOrderKeyAmountOrCurrency() {
        charge();
        assertThatThrownBy(() -> service.processPayment("order", "other", new BigDecimal("100"), "USD", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPayment("other", "user", new BigDecimal("100"), "USD", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPayment("order", "user", new BigDecimal("100"), "USD", "other"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPayment("order", "user", new BigDecimal("101"), "USD", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPayment("order", "user", new BigDecimal("100"), "EUR", "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transientFailureRetainsRequestBindingAndAllowsSameKeyRetry() {
        when(gateway.processPayment(any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentTransientException("timeout")).thenReturn(new GatewayResult("retry", "COMPLETED"));
        assertThatThrownBy(this::charge).isInstanceOf(PaymentTransientException.class);
        assertThat(paymentRows.values().iterator().next().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(cache).isEmpty();
        assertThat(charge().getTransactionId()).isEqualTo("retry");
    }

    @Test
    void declineIsPersistedAndCachedBeforeBeingRethrown() {
        when(gateway.processPayment(any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentDeclinedException("declined"));
        assertThatThrownBy(this::charge).isInstanceOf(PaymentDeclinedException.class);
        assertThatThrownBy(this::charge).isInstanceOf(PaymentDeclinedException.class);
        assertThat(cache.get("key").getResponse()).contains("declined");
        verify(gateway).processPayment(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void activeLeaseIsBusyAndExpiredLeaseIsReclaimedWithFinalizerFencing() {
        var first = intents.prepareCharge("order", "user", BigDecimal.TEN, "USD", "key");
        assertThat(intents.prepareCharge("order", "user", BigDecimal.TEN, "USD", "key").busy()).isTrue();
        first.payment().setProcessingUntil(now.minusSeconds(1));
        var retry = intents.prepareCharge("order", "user", BigDecimal.TEN, "USD", "key");
        assertThat(retry.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> intents.finishCharge(first, new GatewayResult("late", "COMPLETED"), null))
                .isInstanceOf(PaymentTransientException.class);
        assertThat(intents.finishCharge(retry, new GatewayResult("receipt", "COMPLETED"), null).success()).isTrue();
    }

    @Test
    void partialRefundsTrackCumulativeAmountAndReplayBoundKeysWithoutAnotherProviderCall() {
        Payment payment = charge();
        var first = service.refundPayment(payment.getId(), "order", new BigDecimal("40"), "USD", "partial", "one");
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("40");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(service.refundPayment(payment.getId(), "order", new BigDecimal("40"), "USD", "retry", "one"))
                .isEqualTo(first);
        service.refundPayment(payment.getId(), "order", new BigDecimal("60"), "USD", "remaining", "two");
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("100");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(gateway, times(2)).refundPayment(anyString(), any(), anyString());
        assertThatThrownBy(this::charge).isInstanceOf(PaymentDeclinedException.class);
    }

    @Test
    void refundCurrencyAmountBindingAndRemainingBalanceAreValidated() {
        Payment payment = charge();
        assertThatThrownBy(() -> service.refundPayment(payment.getId(), "order", BigDecimal.TEN, "partial without key"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotency key");
        assertThatThrownBy(() -> service.refundPayment(payment.getId(), "order", BigDecimal.TEN, "EUR", "wrong", "one"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.refundPayment(payment.getId(), "other", BigDecimal.TEN, "USD", "wrong", "one"))
                .isInstanceOf(IllegalArgumentException.class);
        service.refundPayment(payment.getId(), "order", new BigDecimal("90"), "USD", "partial", "one");
        assertThatThrownBy(() -> service.refundPayment(payment.getId(), "order", BigDecimal.TEN, "USD", "changed", "one"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.refundPayment(payment.getId(), "order", new BigDecimal("11"), "USD", "over", "two"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uncertainRefundReservesRemainingBalanceForSameKeyRecovery() {
        Payment payment = charge();
        doThrow(new PaymentTransientException("uncertain")).doReturn(new GatewayResult("receipt", "REFUNDED"))
                .when(gateway).refundPayment(anyString(), any(), anyString());
        assertThatThrownBy(() -> service.refundPayment(payment.getId(), "order", BigDecimal.TEN, "USD", "refund", "one"))
                .isInstanceOf(PaymentTransientException.class);
        assertThat(intents.prepareRefund(payment.getId(), "order", BigDecimal.TEN, "USD", "two").busy()).isTrue();
        assertThat(service.refundPayment(payment.getId(), "order", BigDecimal.TEN, "USD", "retry", "one").success()).isTrue();
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("10");
    }

    @Test
    void historicalRefundWithoutReceiptCannotMoveMoneyAgain() {
        Payment payment = charge();
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAmount(null);
        var replay = service.refundPayment(payment.getId(), "order", BigDecimal.TEN, "legacy retry");
        assertThat(replay.transactionId()).isEmpty();
        verify(gateway, never()).refundPayment(anyString(), any(), anyString());
    }

    @Test
    void rejectsInvalidMoneyIdentityAndCurrencyBeforeCreatingIntent() {
        for (BigDecimal amount : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("0.001"),
                new BigDecimal("100000000000000000.00")}) {
            assertThatThrownBy(() -> service.processPayment("order", "user", amount, "USD", "key"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.processPayment("", "user", BigDecimal.TEN, "USD", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPayment("order", null, BigDecimal.TEN, "USD", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPayment("order", "user", BigDecimal.TEN, "", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.refundPayment(-1L, "order", BigDecimal.TEN, "USD", "bad", "one"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(paymentRows).isEmpty();
    }

    @Test
    void reconciliationReadsBoundedPageAndReportsHasNext() {
        Payment payment = charge();
        when(payments.findAll(PageRequest.of(0, 1))).thenReturn(new PageImpl<>(List.of(payment), PageRequest.of(0, 1), 2));
        var page = service.getReconciliationSummary(0, 1);
        assertThat(page.payments()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(service.findByOrderId("order")).contains(payment);
        assertThatThrownBy(() -> service.getReconciliationSummary(0, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
