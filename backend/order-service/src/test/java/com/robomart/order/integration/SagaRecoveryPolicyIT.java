package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.saga.RecoveryPhase;
import com.robomart.order.saga.SagaRecoveryPolicy;
import com.robomart.test.PostgresContainerConfig;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"saga.dead-saga-detection.initial-delay-ms=3600000", "saga.payment-recovery.enabled=false"})
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, TestJwtDecoderConfig.class})
class SagaRecoveryPolicyIT {
    @MockitoBean @SuppressWarnings("rawtypes") private KafkaTemplate kafkaTemplate;
    @Autowired private SagaRecoveryPolicy policy;
    @Autowired private SagaProperties properties;
    @Autowired private OrderRepository orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meters;
    @Autowired private TransactionTemplate transactions;
    @LocalServerPort private int port;
    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        created.forEach(id -> jdbc.update("DELETE FROM orders WHERE id=?", id));
    }

    private Order order(String status, Instant createdAt) {
        Long id = jdbc.queryForObject("INSERT INTO orders(user_id, total_amount, status, reservation_id, "
                + "created_at, updated_at) VALUES ('manual-review-test', 10, ?, 'keep-reserved', ?, now()) RETURNING id",
                Long.class, status, Timestamp.from(createdAt));
        created.add(id);
        return orders.findById(id).orElseThrow();
    }

    private double escalations(RecoveryPhase phase) {
        return meters.counter("robomart.saga.manual.review", "phase", phase.name()).count();
    }

    @Test
    void exhaustedAttemptsPersistManualFlagAndEmitMetricOnce() {
        Order order = order("REFUND_FAILED", Instant.now());
        int limit = properties.getRefundRecovery().getMaxAttempts();
        double before = escalations(RecoveryPhase.REFUND);
        try {
            properties.getRefundRecovery().setMaxAttempts(1);
            assertThat(policy.tryBegin(order, RecoveryPhase.REFUND)).isTrue();
            policy.failed(order.getId(), RecoveryPhase.REFUND, "Uncertain refund");
            assertThat(policy.tryBegin(order, RecoveryPhase.REFUND)).isFalse();
            assertThat(policy.isManualReview(order.getId())).isTrue();
            assertThat(escalations(RecoveryPhase.REFUND)).isEqualTo(before + 1);
            assertThat(policy.manualReviews(0, 100)).anySatisfy(review -> {
                assertThat(review.orderId()).isEqualTo(order.getId());
                assertThat(review.attempts()).isEqualTo(1);
            });
            assertThat(orders.findById(order.getId()).orElseThrow().getStatus().name()).isEqualTo("REFUND_FAILED");
        } finally {
            properties.getRefundRecovery().setMaxAttempts(limit);
        }
    }

    @Test
    void refundAgeStartsAtRefundRatherThanOriginalPurchase() {
        Order oldPurchase = order("REFUND_FAILED", Instant.now().minusSeconds(86400 * 90));
        assertThat(policy.tryBegin(oldPurchase, RecoveryPhase.REFUND)).isTrue();
        jdbc.update("UPDATE saga_recovery_state SET started_at=now() - interval '2 hours' WHERE order_id=?",
                oldPurchase.getId());
        policy.failed(oldPurchase.getId(), RecoveryPhase.REFUND, "Still uncertain");
        assertThat(policy.isManualReview(oldPurchase.getId())).isTrue();
        assertThat(policy.tryBegin(oldPurchase, RecoveryPhase.REFUND)).isFalse();
    }

    @Test
    void concurrentRefundClaimsAllowOnlyOneRpcBudget() throws Exception {
        Order order = order("REFUND_FAILED", Instant.now());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return policy.tryBegin(order, RecoveryPhase.REFUND);
                }));
            }
            start.countDown();
            int claimed = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get(10, TimeUnit.SECONDS)) {
                    claimed++;
                }
            }
            assertThat(claimed).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT attempts FROM saga_recovery_state WHERE order_id=?",
                    Integer.class, order.getId())).isEqualTo(1);
        }
    }

    @Test
    void callerRollbackDoesNotLoseCommittedEscalation() {
        Order order = order("PAYMENT_PENDING", Instant.now().minusSeconds(7200));
        double before = escalations(RecoveryPhase.PAYMENT);
        transactions.execute(status -> {
            assertThat(policy.tryBegin(order, RecoveryPhase.PAYMENT)).isFalse();
            status.setRollbackOnly();
            return null;
        });
        assertThat(policy.isManualReview(order.getId())).isTrue();
        assertThat(escalations(RecoveryPhase.PAYMENT)).isEqualTo(before + 1);
    }

    @Test
    void onlyAdminCanInspectAndResumeManualRecovery() {
        Order order = order("PAYMENT_PENDING", Instant.now().minusSeconds(7200));
        assertThat(policy.tryBegin(order, RecoveryPhase.PAYMENT)).isFalse();
        for (String token : List.of("", TestJwt.customerBearer(), "Bearer " + TestJwt.NOTIFICATION_TOKEN)) {
            RestClient client = client(token);
            int expected = token.isEmpty() ? 401 : 403;
            assertThat(client.get().uri("/api/v1/admin/orders/recovery/manual-reviews")
                    .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(expected);
            assertThat(client.post().uri("/api/v1/admin/orders/recovery/{id}/PAYMENT/resume", order.getId())
                    .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(expected);
        }
        assertThat(client(TestJwt.adminBearer()).get().uri("/api/v1/admin/orders/recovery/manual-reviews")
                .retrieve().body(String.class)).contains(order.getId().toString());
        assertThat(client(TestJwt.adminBearer()).post()
                .uri("/api/v1/admin/orders/recovery/{id}/PAYMENT/resume", order.getId())
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(204);
        assertThat(policy.isManualReview(order.getId())).isFalse();
        assertThat(policy.tryBegin(order, RecoveryPhase.PAYMENT)).isTrue();
        assertThat(orders.findById(order.getId()).orElseThrow().getReservationId()).isEqualTo("keep-reserved");
    }

    @Test
    void callerCrashDoesNotResetConsumedAttemptBudget() {
        Order order = order("PAYMENT_PENDING", Instant.now());
        transactions.execute(status -> {
            jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR NO KEY UPDATE", Long.class, order.getId());
            assertThat(policy.tryBegin(order, RecoveryPhase.PAYMENT)).isTrue();
            status.setRollbackOnly();
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT attempts FROM saga_recovery_state WHERE order_id=?",
                Integer.class, order.getId())).isEqualTo(1);
    }

    private RestClient client(String token) {
        var builder = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { });
        if (!token.isEmpty()) {
            builder.defaultHeader("Authorization", token);
        }
        return builder.build();
    }
}
