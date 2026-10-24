package com.robomart.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.payment.repository.IdempotencyKeyRepository;
import com.robomart.payment.repository.OutboxEventRepository;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.service.MockGatewayLedger;
import com.robomart.payment.service.GatewayResult;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.service.MockPaymentGateway;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.service.PaymentService.PaymentResult;
import com.robomart.test.PostgresContainerConfig;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.grpc.server.port=0", "spring.datasource.hikari.maximum-pool-size=2",
        "management.endpoint.health.validate-group-membership=false"
})
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
class PaymentIdempotencyIT {

    @Autowired private PaymentService service;
    @Autowired private PaymentRepository payments;
    @Autowired private IdempotencyKeyRepository keys;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private MockPaymentGateway gateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private ObjectMapper mapper;
    @Autowired @Qualifier("mockGatewayDataSource") private DataSource providerDataSource;

    @BeforeEach
    void clearFixtures() {
        gateway.reset();
        outbox.deleteAll();
        keys.deleteAll();
        payments.deleteAll();
        jdbc.update("DELETE FROM mock_gateway_operations");
    }

    @Test
    void settledPaymentSurvivesExpiredOrMissingResponseCache() {
        PaymentResult first = pay("expiry", "key-expiry");
        var cached = keys.findByIdempotencyKey("key-expiry").orElseThrow();
        cached.setExpiresAt(Instant.now().minusSeconds(86400));
        keys.save(cached);
        assertThat(pay("expiry", "key-expiry")).isEqualTo(first);
        keys.deleteAll();
        gateway.reset(); // A process restart/fault reset must not erase settlement history.
        assertThat(pay("expiry", "key-expiry")).isEqualTo(first);
        assertOneChargeAndEvent("expiry");
    }

    @Test
    void concurrentReplaysUseOneChargeOnePaymentAndOneOutboxWithSmallApplicationPool() throws Exception {
        gateway.setSimulatedDelayMs(100);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<PaymentResult>> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return pay("concurrent", "key-concurrent");
                }));
            }
            start.countDown();
            PaymentResult first = requests.getFirst().get(15, TimeUnit.SECONDS);
            for (Future<PaymentResult> request : requests) {
                assertThat(request.get(15, TimeUnit.SECONDS)).isEqualTo(first);
            }
        }
        assertOneChargeAndEvent("concurrent");
    }

    @Test
    void concurrentDifferentKeysCannotChargeTheSameOrderTwice() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (String key : List.of("key-first", "key-second")) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    try {
                        return pay("one-order", key).success();
                    } catch (IllegalArgumentException expectedConflict) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var attempt : attempts) {
                successes += attempt.get(15, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);
        }
        assertOneChargeAndEvent("one-order");
    }

    @Test
    void providerSettlementSurvivesCallerRollbackAndGatewayRecreation() {
        PaymentResult ambiguous = transactions.execute(status -> {
            PaymentResult result = pay("ambiguous", "key-ambiguous");
            status.setRollbackOnly();
            return result;
        });
        assertThat(payments.count()).isZero();
        assertThat(outbox.count()).isZero();
        assertThat(operationCount("charge:%")).isEqualTo(1);
        var restartedGateway = new MockPaymentGateway(new MockGatewayLedger(providerDataSource));
        var restartedService = new PaymentService(payments, keys, outbox, restartedGateway, transactions, mapper);
        PaymentResult recovered = restartedService.processPayment("ambiguous", "user-1",
                new BigDecimal("29.99"), "USD", "key-ambiguous");
        assertThat(recovered.transactionId()).isEqualTo(ambiguous.transactionId());
        assertOneChargeAndEvent("ambiguous");
    }

    @Test
    void sameKeyCannotBeReboundToAnotherUserOrAmountAfterRestart() {
        pay("bound", "key-bound");
        assertThatThrownBy(() -> service.processPayment("bound", "another-user",
                new BigDecimal("29.99"), "USD", "key-bound")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPayment("bound", "user-1",
                new BigDecimal("30.99"), "USD", "key-bound")).isInstanceOf(IllegalArgumentException.class);
        assertOneChargeAndEvent("bound");
    }

    @Test
    void refundAndChargeReplaysCannotMoveMoneyTwice() {
        PaymentResult charge = pay("refund", "key-refund");
        PaymentResult first = service.refundPayment(Long.valueOf(charge.paymentId()), "refund",
                new BigDecimal("29.99"), "test cleanup");
        PaymentResult replay = service.refundPayment(Long.valueOf(charge.paymentId()), "refund",
                new BigDecimal("29.99"), "test replay");
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThatThrownBy(() -> pay("refund", "key-refund"))
                .isInstanceOf(com.robomart.payment.exception.PaymentDeclinedException.class);
        assertThat(operationCount("charge:%")).isEqualTo(1);
        assertThat(operationCount("refund:%")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(2);
    }

    @Test
    void providerKeySerializesIndependentGatewayInstances() throws Exception {
        var first = new MockGatewayLedger(providerDataSource);
        var second = new MockGatewayLedger(providerDataSource);
        var settlements = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<GatewayResult>> results = new ArrayList<>();
            for (MockGatewayLedger instance : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    start.await();
                    return instance.settle("charge:independent", new BigDecimal("29.99"), "USD", "same-order-user",
                            () -> new GatewayResult("txn-" + settlements.incrementAndGet(), "COMPLETED"));
                }));
            }
            start.countDown();
            assertThat(results.getFirst().get(10, TimeUnit.SECONDS))
                    .isEqualTo(results.getLast().get(10, TimeUnit.SECONDS));
        }
        assertThat(settlements.get()).isEqualTo(1);
        assertThat(operationCount("charge:%")).isEqualTo(1);
    }

    @Test
    void declinedSettlementCannotBecomeAChargeAfterExpiryOrGatewayRecreation() {
        gateway.setSimulatePermanentFailure(true);
        assertThatThrownBy(() -> pay("declined", "key-declined")).isInstanceOf(PaymentDeclinedException.class);
        var cached = keys.findByIdempotencyKey("key-declined").orElseThrow();
        cached.setExpiresAt(Instant.now().minusSeconds(86400));
        keys.save(cached);
        gateway.reset();
        assertThatThrownBy(() -> pay("declined", "key-declined")).isInstanceOf(PaymentDeclinedException.class);
        // Even losing the caller's cache cannot turn the provider's definitive decline into a new charge.
        keys.deleteAll();
        var restarted = new MockPaymentGateway(new MockGatewayLedger(providerDataSource));
        var restartedService = new PaymentService(payments, keys, outbox, restarted, transactions, mapper);
        assertThatThrownBy(() -> restartedService.processPayment("declined", "user-1",
                new BigDecimal("29.99"), "USD", "key-declined"))
                .isInstanceOf(PaymentDeclinedException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM mock_gateway_operations WHERE operation_key=?",
                String.class, "charge:key-declined")).isEqualTo("DECLINED");
        assertThat(outbox.count()).isZero();
    }

    @Test
    void ambiguousProviderChargeCannotBeReboundToAnotherUserBeforeFinalization() {
        transactions.executeWithoutResult(status -> {
            pay("unfinalized", "key-unfinalized");
            status.setRollbackOnly();
        });
        assertThat(payments.count()).isZero();
        var restarted = new MockPaymentGateway(new MockGatewayLedger(providerDataSource));
        var restartedService = new PaymentService(payments, keys, outbox, restarted, transactions, mapper);
        assertThatThrownBy(() -> restartedService.processPayment("unfinalized", "another-user",
                new BigDecimal("29.99"), "USD", "key-unfinalized"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(operationCount("charge:%")).isEqualTo(1);
        assertThat(payments.count()).isZero();
    }

    @Test
    void refundSettlementSurvivesCallerRollbackAndGatewayRecreation() {
        PaymentResult charge = pay("ambiguous-refund", "key-ambiguous-refund");
        PaymentResult ambiguous = transactions.execute(status -> {
            PaymentResult result = service.refundPayment(Long.valueOf(charge.paymentId()), "ambiguous-refund",
                    new BigDecimal("29.99"), "test refund");
            status.setRollbackOnly();
            return result;
        });
        var restarted = new MockPaymentGateway(new MockGatewayLedger(providerDataSource));
        var restartedService = new PaymentService(payments, keys, outbox, restarted, transactions, mapper);
        PaymentResult recovered = restartedService.refundPayment(Long.valueOf(charge.paymentId()), "ambiguous-refund",
                new BigDecimal("29.99"), "retry refund");
        assertThat(recovered.transactionId()).isEqualTo(ambiguous.transactionId());
        assertThat(operationCount("refund:%")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(2);
    }

    @Test
    void legacyRefundedPaymentWithoutReceiptIsAcceptedWithoutAnotherGatewayRefund() {
        PaymentResult charge = pay("legacy-refund", "key-legacy-refund");
        jdbc.update("UPDATE payments SET status='REFUNDED', refund_transaction_id=NULL WHERE id=?",
                Long.valueOf(charge.paymentId()));
        PaymentResult replay = service.refundPayment(Long.valueOf(charge.paymentId()), "legacy-refund",
                new BigDecimal("29.99"), "legacy replay");
        assertThat(replay.success()).isTrue();
        assertThat(replay.transactionId()).isEmpty();
        assertThat(operationCount("refund:%")).isZero();
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void refundPrecisionIsRejectedBeforeCreatingAnyProviderOperation() {
        PaymentResult charge = pay("refund-precision", "key-refund-precision");
        for (String amount : List.of("0.001", "100000000000000000.00")) {
            assertThatThrownBy(() -> service.refundPayment(Long.valueOf(charge.paymentId()), "refund-precision",
                    new BigDecimal(amount), "invalid precision"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 decimal places");
        }
        assertThat(operationCount("refund:%")).isZero();
        assertThat(payments.findByOrderId("refund-precision").orElseThrow().getStatus())
                .isEqualTo(com.robomart.payment.enums.PaymentStatus.COMPLETED);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void ambiguousChargeBindsOrderEvenWhenRetryChangesIdempotencyKey() {
        transactions.executeWithoutResult(status -> {
            pay("ambiguous-key-change", "key-original");
            status.setRollbackOnly();
        });
        assertThat(payments.count()).isZero();
        assertThatThrownBy(() -> pay("ambiguous-key-change", "key-replacement"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already bound");
        assertThat(operationCount("charge:%")).isEqualTo(1);
        assertThat(payments.count()).isZero();
        assertThat(pay("ambiguous-key-change", "key-original").success()).isTrue();
        assertOneChargeAndEvent("ambiguous-key-change");
    }

    private PaymentResult pay(String order, String key) {
        return service.processPayment(order, "user-1", new BigDecimal("29.99"), "USD", key);
    }

    private int operationCount(String keyPattern) {
        return jdbc.queryForObject("SELECT count(*) FROM mock_gateway_operations WHERE operation_key LIKE ?",
                Integer.class, keyPattern);
    }

    private void assertOneChargeAndEvent(String order) {
        assertThat(payments.findByOrderId(order)).isPresent();
        assertThat(payments.count()).isEqualTo(1);
        assertThat(operationCount("charge:%")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }
}
