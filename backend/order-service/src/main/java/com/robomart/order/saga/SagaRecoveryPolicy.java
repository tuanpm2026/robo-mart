package com.robomart.order.saga;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;

import io.micrometer.core.instrument.MeterRegistry;

/** Durable per-phase attempt budgets. Escalation never cancels orders or releases stock. */
@Service
public class SagaRecoveryPolicy {
    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryPolicy.class);
    private final JdbcTemplate jdbc;
    private final SagaProperties properties;
    private final MeterRegistry meters;

    public SagaRecoveryPolicy(JdbcTemplate jdbc, SagaProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryBegin(Order order, RecoveryPhase phase) {
        Instant now = Instant.now();
        Instant started = phase == RecoveryPhase.PAYMENT ? order.getCreatedAt() : now;
        jdbc.update("INSERT INTO saga_recovery_state(order_id, phase, started_at) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", order.getId(), phase.name(), Timestamp.from(started));
        State state = lock(order.getId(), phase);
        if (state.completed() || state.manualReview()) {
            return false;
        }
        if (exhausted(state, phase, now)) {
            escalate(order.getId(), phase, "Recovery budget exhausted");
            return false;
        }
        if (state.retryAfter() != null && state.retryAfter().isAfter(now)) {
            return false;
        }
        // Refund intent commits before its RPC. A bounded lease prevents another worker claiming
        // the same phase while that RPC is active. Payment already holds the enclosing order lock.
        Duration lease = phase == RecoveryPhase.REFUND
                ? properties.getSteps().timeoutFor("RefundPaymentStep").multipliedBy(3).plusSeconds(30)
                : Duration.ZERO;
        jdbc.update("UPDATE saga_recovery_state SET attempts=attempts+1, retry_after=? "
                + "WHERE order_id=? AND phase=?", Timestamp.from(now.plus(lease)), order.getId(), phase.name());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long orderId, RecoveryPhase phase, String reason) {
        State state = lock(orderId, phase);
        jdbc.update("UPDATE saga_recovery_state SET last_error=? WHERE order_id=? AND phase=?",
                reason, orderId, phase.name());
        if (!state.completed() && !state.manualReview() && exhausted(state, phase, Instant.now())) {
            escalate(orderId, phase, reason);
        }
    }

    @Transactional
    public void completed(Long orderId, RecoveryPhase phase) {
        jdbc.update("UPDATE saga_recovery_state SET completed_at=?, manual_review_at=NULL, retry_after=NULL "
                + "WHERE order_id=? AND phase=?", Timestamp.from(Instant.now()), orderId, phase.name());
    }

    @Transactional(readOnly = true)
    public List<ManualReview> manualReviews(int page, int size) {
        return jdbc.query("SELECT order_id, phase, started_at, attempts, manual_review_at, last_error "
                + "FROM saga_recovery_state WHERE manual_review_at IS NOT NULL AND completed_at IS NULL "
                + "ORDER BY manual_review_at, order_id, phase LIMIT ? OFFSET ?",
                (rs, row) -> new ManualReview(rs.getLong(1), RecoveryPhase.valueOf(rs.getString(2)),
                        rs.getTimestamp(3).toInstant(), rs.getInt(4), rs.getTimestamp(5).toInstant(), rs.getString(6)),
                size, (long) page * size);
    }

    @Transactional(readOnly = true)
    public boolean isCompleted(Long orderId, RecoveryPhase phase) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM saga_recovery_state "
                + "WHERE order_id=? AND phase=? AND completed_at IS NOT NULL)", Boolean.class, orderId, phase.name()));
    }

    @Transactional(readOnly = true)
    public boolean isManualReview(Long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM saga_recovery_state "
                + "WHERE order_id=? AND manual_review_at IS NOT NULL AND completed_at IS NULL)", Boolean.class, orderId));
    }

    /** Admin explicitly grants a fresh budget; all RPCs still reuse the original payment key. */
    @Transactional
    public boolean resume(Long orderId, RecoveryPhase phase) {
        if (jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE", Long.class, orderId).isEmpty()) {
            return false;
        }
        int resumed = jdbc.update("UPDATE saga_recovery_state s SET attempts=0, started_at=?, manual_review_at=NULL, "
                + "retry_after=NULL, last_error=NULL WHERE order_id=? AND phase=? AND manual_review_at IS NOT NULL "
                + "AND completed_at IS NULL AND EXISTS (SELECT 1 FROM orders o WHERE o.id=s.order_id AND "
                + "((s.phase='PAYMENT' AND o.status='PAYMENT_PENDING') OR "
                + "(s.phase='REFUND' AND o.status IN ('REFUND_FAILED','PAYMENT_REFUNDING'))))",
                Timestamp.from(Instant.now()), orderId, phase.name());
        if (resumed == 1 && phase == RecoveryPhase.PAYMENT) {
            jdbc.update("UPDATE orders SET next_payment_retry_at=? WHERE id=?", Timestamp.from(Instant.now()), orderId);
        }
        return resumed == 1;
    }

    private State lock(Long orderId, RecoveryPhase phase) {
        return jdbc.queryForObject("SELECT started_at, attempts, manual_review_at, retry_after, completed_at "
                + "FROM saga_recovery_state WHERE order_id=? AND phase=? FOR UPDATE", (rs, row) -> {
                    Timestamp retry = rs.getTimestamp(4);
                    return new State(rs.getTimestamp(1).toInstant(), rs.getInt(2), rs.getTimestamp(3) != null,
                            retry == null ? null : retry.toInstant(), rs.getTimestamp(5) != null);
                }, orderId, phase.name());
    }

    private boolean exhausted(State state, RecoveryPhase phase, Instant now) {
        SagaProperties.RecoveryLimits limits = phase == RecoveryPhase.PAYMENT
                ? properties.getPaymentRecovery() : properties.getRefundRecovery();
        return state.attempts() >= limits.getMaxAttempts()
                || !state.startedAt().plus(limits.getMaxAge()).isAfter(now);
    }

    private void escalate(Long orderId, RecoveryPhase phase, String reason) {
        jdbc.update("UPDATE saga_recovery_state SET manual_review_at=?, last_error=? WHERE order_id=? AND phase=?",
                Timestamp.from(Instant.now()), reason, orderId, phase.name());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.error("Saga requires manual review: orderId={}, phase={}, reason={}", orderId, phase, reason);
                meters.counter("robomart.saga.manual.review", "phase", phase.name()).increment();
            }
        });
    }

    private record State(Instant startedAt, int attempts, boolean manualReview, Instant retryAfter, boolean completed) { }

    public record ManualReview(Long orderId, RecoveryPhase phase, Instant startedAt, int attempts,
            Instant manualReviewAt, String reason) { }
}
