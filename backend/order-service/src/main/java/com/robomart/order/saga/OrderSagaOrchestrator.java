package com.robomart.order.saga;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.entity.SagaAuditLog;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderNotCancellableException;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.saga.steps.ProcessPaymentStep;
import com.robomart.order.saga.steps.RefundPaymentStep;
import com.robomart.order.saga.steps.ReleaseInventoryStep;
import com.robomart.order.saga.steps.ReserveInventoryStep;

import io.grpc.Status;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the order saga. Every status change is a compare-and-set ({@link #transition}): whoever
 * moves the order out of a state owns it, and anyone who loses that race stops instead of acting on
 * an order that cancellation or recovery has already taken over. Stale sagas are recovered only by
 * the ShedLock-guarded {@link DeadSagaDetectionJob}, which applies an age cutoff; there is no
 * startup sweep, because a starting replica cannot tell its peers' live sagas from dead ones.
 */
@Service
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SagaAuditLogRepository sagaAuditLogRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final SagaProperties sagaProperties;
    private final List<SagaStep> forwardSteps;
    private final ReleaseInventoryStep releaseInventoryStep;
    private final RefundPaymentStep refundPaymentStep;

    public OrderSagaOrchestrator(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            SagaAuditLogRepository sagaAuditLogRepository,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            SagaProperties sagaProperties,
            ReserveInventoryStep reserveInventoryStep,
            ProcessPaymentStep processPaymentStep,
            ReleaseInventoryStep releaseInventoryStep,
            RefundPaymentStep refundPaymentStep) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.sagaAuditLogRepository = sagaAuditLogRepository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.sagaProperties = sagaProperties;
        this.forwardSteps = List.of(reserveInventoryStep, processPaymentStep);
        this.releaseInventoryStep = releaseInventoryStep;
        this.refundPaymentStep = refundPaymentStep;
    }

    public void executeSaga(Order order) {
        String sagaId = order.getId().toString();
        SagaContext context = new SagaContext(order);

        OrderStatus[] stepTargetStates = {OrderStatus.INVENTORY_RESERVING, OrderStatus.PAYMENT_PROCESSING};

        for (int i = 0; i < forwardSteps.size(); i++) {
            SagaStep step = forwardSteps.get(i);
            OrderStatus targetState = stepTargetStates[i];

            // Losing this CAS means cancellation or recovery owns the order: run no further step.
            if (!transition(order, order.getStatus(), targetState, null)) {
                abortSaga(context, targetState, i > 0);
                return;
            }

            try {
                executeStep(step, context, 0);
            } catch (SagaStepException e) {
                if (e.getCancellationReason() != null) {
                    order.setCancellationReason(e.getCancellationReason());
                }
                if (e.isShouldHoldAsPending()) {
                    order.setNextPaymentRetryAt(Instant.now()
                            .plus(sagaProperties.getPaymentRecovery().getRetryDelay()));
                    if (!transition(order, targetState, OrderStatus.PAYMENT_PENDING, targetState)) {
                        abortSaga(context, OrderStatus.PAYMENT_PENDING, true);
                        return;
                    }
                    log.info("Order held as PAYMENT_PENDING for orderId={}", sagaId);
                    return;
                }
                failSaga(context, step, targetState, e.isShouldCompensate());
                return;
            }
        }

        if (!transition(order, OrderStatus.PAYMENT_PROCESSING, OrderStatus.CONFIRMED,
                OrderStatus.PAYMENT_PROCESSING)) {
            abortSaga(context, OrderStatus.CONFIRMED, true);
            return;
        }
        log.info("Saga completed successfully for sagaId={}", sagaId);
    }

    /**
     * Cancels the order after a failed forward step. When compensation is needed the order is first
     * claimed as INVENTORY_RELEASING, so stock is released only by the actor that owns the order and a
     * crash mid-release is still picked up by dead-saga recovery.
     */
    private void failSaga(SagaContext context, SagaStep step, OrderStatus stepState, boolean compensate) {
        Order order = context.getOrder();
        String sagaId = order.getId().toString();
        if (compensate) {
            if (!transition(order, stepState, OrderStatus.INVENTORY_RELEASING, null)) {
                abortSaga(context, OrderStatus.INVENTORY_RELEASING, true);
                return;
            }
            if (!runCompensation(context, sagaId)) {
                return;
            }
        }
        if (!transition(order, order.getStatus(), OrderStatus.CANCELLED, stepState)) {
            abortSaga(context, OrderStatus.CANCELLED, !compensate);
            return;
        }
        log.warn("Saga failed at step={} for sagaId={}, cancellationReason={}",
                step.getName(), sagaId, order.getCancellationReason());
    }

    /**
     * Stops a saga that lost a status CAS. The in-memory order adopts the persisted status so callers
     * report what actually happened; no CONFIRMED (or any other) event is published by this saga.
     */
    private void abortSaga(SagaContext context, OrderStatus attempted, boolean inventoryMayBeReserved) {
        Order order = context.getOrder();
        OrderStatus actual = orderRepository.findStatusById(order.getId()).orElse(null);
        log.warn("Saga aborted for orderId={}: could not move to {} because the order is now {} "
                 + "(taken over by cancellation or recovery)", order.getId(), attempted, actual);
        logSagaStep(order, "SAGA", "ABORTED", null, null,
                    "Lost transition to " + attempted + "; current status " + actual, 0);
        if (actual == null) {
            return;
        }
        order.setStatus(actual);
        if (actual == OrderStatus.CANCELLED && inventoryMayBeReserved) {
            // The canceller may have released before this saga's reservation landed. Release is
            // idempotent by orderId, so repeat it rather than leak stock.
            runCompensation(context, order.getId().toString());
        }
        if (actual == OrderStatus.CANCELLED && order.getPaymentId() != null) {
            log.error("Order {} was cancelled concurrently after payment {} was taken — refund requires "
                      + "manual reconciliation", order.getId(), order.getPaymentId());
        }
    }

    /**
     * Executes a saga step on the caller thread with an RPC deadline and idempotency check.
     * Idempotency key: "{orderId}:{stepName}" prevents duplicate execution on retries.
     */
    private void executeStep(SagaStep step, SagaContext context, int retryCount) {
        String sagaId = context.getOrder().getId().toString();
        String idempotencyKey = sagaId + ":" + step.getName();

        if (sagaAuditLogRepository.existsByIdempotencyKeyAndStatus(idempotencyKey, "SUCCESS")) {
            log.info("Saga step [{}] idempotent — already succeeded for saga {}, skipping",
                     step.getName(), sagaId);
            return;
        }

        logSagaStep(context.getOrder(), step.getName(), "STARTED",
                    idempotencyKey, null, null, retryCount);
        // gRPC enforces the deadline; no background task can mutate this order after return.
        try {
            SagaStepResult result = Objects.requireNonNull(step.execute(context), "Missing saga step result");
            if (result.reservationId() != null) {
                context.getOrder().setReservationId(result.reservationId());
            }
            if (result.paymentId() != null) {
                context.getOrder().setPaymentId(result.paymentId());
            }
            recordStepSuccess(context.getOrder(), step.getName(), idempotencyKey, retryCount);
        } catch (RuntimeException e) {
            boolean timedOut = Status.fromThrowable(e).getCode() == Status.Code.DEADLINE_EXCEEDED;
            logSagaStep(context.getOrder(), step.getName(), timedOut ? "TIMED_OUT" : "FAILED",
                    idempotencyKey, timedOut ? Instant.now() : null, e.getMessage(), retryCount);
            if (e instanceof SagaStepException failure) {
                throw failure;
            }
            boolean payment = step instanceof ProcessPaymentStep;
            throw new SagaStepException("Step execution failed", e, !payment, payment);
        }
    }

    private boolean runCompensation(SagaContext context, String sagaId) {
        Order order = context.getOrder();
        logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATING", null, null, null, 0);
        try {
            releaseInventoryStep.compensate(context);
            logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATED", null, null, null, 0);
            return true;
        } catch (Exception e) {
            logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATION_FAILED",
                        null, null, e.getMessage(), 0);
            log.error("Compensation failed for sagaId={}: {}", sagaId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Compare-and-set status change: only if the order is still {@code from}, moves it to {@code to}
     * and writes the status history row plus (when {@code eventPreviousStatus} is non-null) the
     * order_status_changed outbox event in the same transaction.
     *
     * @return {@code false}, with nothing written, if another actor moved the order first
     */
    private boolean transition(Order order, OrderStatus from, OrderStatus to, OrderStatus eventPreviousStatus) {
        return transitionWithEvent(order, from, to, eventPreviousStatus == null ? null
                : () -> statusChangedEvent(order, eventPreviousStatus, to));
    }

    private boolean transitionWithEvent(Order order, OrderStatus from, OrderStatus to,
            Supplier<OutboxEvent> event) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!orderRepository.transitionStatus(order, from, to)) {
                return false;
            }
            order.setStatus(to);
            OrderStatusHistory history = new OrderStatusHistory();
            history.setOrder(order);
            history.setStatus(to);
            history.setChangedAt(Instant.now());
            orderStatusHistoryRepository.save(history);
            if (event != null) {
                outboxEventRepository.save(event.get());
            }
            return true;
        }));
    }

    private OutboxEvent statusChangedEvent(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.getId().toString());
        payload.put("previousStatus", previousStatus.name());
        payload.put("newStatus", newStatus.name());
        return new OutboxEvent("Order", order.getId().toString(), "order_status_changed",
                objectMapper.writeValueAsString(payload));
    }

    /**
     * Records a successful step by persisting the step output (notably the {@code reservationId} set
     * by {@link ReserveInventoryStep}) and the SUCCESS audit log in one transaction, so a crash before
     * the next status transition cannot lose it. The output is written only while the order is still
     * in this saga's state. Best-effort: a persistence failure is logged but does not abort the saga
     * (the next transition re-persists the order, and inventory can still be released by orderId).
     */
    private void recordStepSuccess(Order order, String stepName, String idempotencyKey, int retryCount) {
        OrderStatus current = order.getStatus();
        try {
            transactionTemplate.execute(status -> {
                if (!orderRepository.transitionStatus(order, current, current)) {
                    log.warn("Step {} succeeded for orderId={} but the order left {} — output not persisted",
                             stepName, order.getId(), current);
                }
                sagaAuditLogRepository.save(
                        buildSagaAuditLog(order, stepName, "SUCCESS", idempotencyKey, null, null, retryCount));
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to record step success for orderId={}, step={}: {}",
                      order.getId(), stepName, e.getMessage(), e);
        }
    }

    private void logSagaStep(Order order, String stepName, String status,
            String idempotencyKey, Instant timeoutAt, String error, int retryCount) {
        try {
            sagaAuditLogRepository.save(
                    buildSagaAuditLog(order, stepName, status, idempotencyKey, timeoutAt, error, retryCount));
        } catch (Exception e) {
            log.error("Failed to write saga audit log for orderId={}, step={}: {}",
                      order.getId(), stepName, e.getMessage());
        }
    }

    private SagaAuditLog buildSagaAuditLog(Order order, String stepName, String status,
            String idempotencyKey, Instant timeoutAt, String error, int retryCount) {
        String sagaId = order.getId().toString();
        SagaAuditLog entry = new SagaAuditLog();
        entry.setSagaId(sagaId);
        entry.setOrderId(sagaId);
        entry.setStepName(stepName);
        entry.setStatus(status);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setTimeoutAt(timeoutAt);
        entry.setError(error);
        entry.setRetryCount(retryCount);
        entry.setExecutedAt(Instant.now());
        return entry;
    }

    public void cancelPendingSaga(Order order, String reason, String cancelledBy) {
        if (order.getReservationId() == null) {
            if (!finalizeCancellation(order, OrderStatus.PENDING, reason, cancelledBy)) {
                throw notCancellable(order);
            }
            return;
        }
        String previousReason = order.getCancellationReason();
        order.setCancellationReason(reason);
        if (!transition(order, OrderStatus.PENDING, OrderStatus.INVENTORY_RELEASING, null)) {
            order.setCancellationReason(previousReason);
            throw notCancellable(order);
        }
        if (!runCompensation(new SagaContext(order), order.getId().toString())) {
            return;
        }
        finalizeCancellation(order, OrderStatus.INVENTORY_RELEASING, reason, cancelledBy);

        log.info("Pending order cancelled for orderId={}", order.getId());
    }

    public void cancelConfirmedSaga(Order order, String reason, String cancelledBy) {
        String previousReason = order.getCancellationReason();
        order.setCancellationReason(reason);
        // CAS from CONFIRMED: if the order was concurrently shipped or delivered, refuse rather than
        // refund goods that have left the warehouse.
        if (!transition(order, OrderStatus.CONFIRMED, OrderStatus.PAYMENT_REFUNDING, null)) {
            order.setCancellationReason(previousReason);
            throw notCancellable(order);
        }

        if (!attemptRefund(order)) {
            // Refund failed — do NOT proceed to release/CANCELLED. Marking the order CANCELLED here
            // would strand the customer's money with no retry path. Hold it in REFUND_FAILED so the
            // recovery scan retries the refund; inventory stays reserved until the order truly cancels.
            markRefundFailed(order);
            log.error("Confirmed-order cancellation halted at refund for orderId={} — customer NOT yet "
                      + "refunded; held in REFUND_FAILED for retry", order.getId());
            return;
        }

        if (!transition(order, OrderStatus.PAYMENT_REFUNDING, OrderStatus.INVENTORY_RELEASING,
                null)) {
            log.warn("Order {} left PAYMENT_REFUNDING during cancellation; recovery owns it now", order.getId());
            return;
        }
        if (!runCompensation(new SagaContext(order), order.getId().toString())) {
            return;
        }

        finalizeCancellation(order, OrderStatus.INVENTORY_RELEASING, reason, cancelledBy);
        log.info("Confirmed order cancelled for orderId={}", order.getId());
    }

    private OrderNotCancellableException notCancellable(Order order) {
        OrderStatus actual = orderRepository.findStatusById(order.getId()).orElse(null);
        return new OrderNotCancellableException("Order " + order.getId() + " cannot be cancelled in state: "
                + actual + " (concurrent modification — please retry)");
    }

    /** CAS {@code from} → CANCELLED with history and the order_cancelled outbox event in one transaction. */
    private boolean finalizeCancellation(Order order, OrderStatus from, String reason, String cancelledBy) {
        String previousReason = order.getCancellationReason();
        order.setCancellationReason(reason);
        boolean cancelled = transitionWithEvent(order, from, OrderStatus.CANCELLED, () -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", order.getId().toString());
            payload.put("reason", reason);
            payload.put("cancelledBy", cancelledBy);
            return new OutboxEvent("Order", order.getId().toString(), "order_cancelled",
                    objectMapper.writeValueAsString(payload));
        });
        if (!cancelled) {
            order.setCancellationReason(previousReason);
            log.warn("Cancellation of orderId={} from {} lost to a concurrent change", order.getId(), from);
        }
        return cancelled;
    }

    /**
     * Triggers compensation for a stuck/dead saga detected by scheduled job (AC3, AC5).
     */
    public void handleDeadSaga(Order order) {
        log.warn("Dead saga detected: orderId={}, status={}", order.getId(), order.getStatus());
        logSagaStep(order, "DEAD_SAGA_DETECTION", "RECOVERY",
                    null, null,
                    "Stuck in " + order.getStatus() + " — triggering recovery", 0);
        recoverSingleSaga(order);
    }

    private void recoverSingleSaga(Order order) {
        if (order.getStatus() == OrderStatus.PAYMENT_PROCESSING) {
            requeueUncertainPayment(order.getId());
            return;
        }
        String sagaId = order.getId().toString();
        String staleStatus = order.getStatus().name();
        String recoveryReason = "Recovered from stale state: " + staleStatus;

        if (order.getStatus() == OrderStatus.PAYMENT_REFUNDING
                || order.getStatus() == OrderStatus.REFUND_FAILED) {
            if (!attemptRefund(order)) {
                // Still cannot refund — keep the order in REFUND_FAILED (money owed) so the next
                // recovery cycle retries. Never finalize as CANCELLED while the refund is unresolved.
                markRefundFailed(order);
                return;
            }
        } else if (order.getStatus() == OrderStatus.INVENTORY_RESERVING) {
            order.setCancellationReason(recoveryReason);
        }
        // Claim the order before releasing stock; a saga still holding it would then lose its next CAS.
        if (order.getStatus() != OrderStatus.INVENTORY_RELEASING
                && !transition(order, order.getStatus(), OrderStatus.INVENTORY_RELEASING, null)) {
            log.warn("Recovery skipped for orderId={}: it left {} concurrently", sagaId, staleStatus);
            return;
        }
        if (!runCompensation(new SagaContext(order), sagaId)) {
            return;
        }
        finalizeCancellation(order, OrderStatus.INVENTORY_RELEASING, recoveryReason, "system");
    }

    private void requeueUncertainPayment(Long orderId) {
        Instant cutoff = Instant.now().minus(sagaProperties.getDeadSagaDetection().getStuckThreshold());
        transactionTemplate.execute(status -> {
            var candidate = orderRepository.findUncertainPaymentForUpdate(orderId, cutoff);
            if (candidate.isEmpty()) {
                // A live checkout, another recovery worker or a completed order owns this row.
                return null;
            }
            Order order = candidate.get();
            order.setStatus(OrderStatus.PAYMENT_PENDING);
            order.setNextPaymentRetryAt(Instant.now());
            OrderStatusHistory history = new OrderStatusHistory();
            history.setOrder(order);
            history.setStatus(OrderStatus.PAYMENT_PENDING);
            history.setChangedAt(Instant.now());
            orderStatusHistoryRepository.save(history);
            outboxEventRepository.save(new OutboxEvent("Order", orderId.toString(), "order_status_changed",
                    objectMapper.writeValueAsString(Map.of("orderId", orderId.toString(),
                            "previousStatus", OrderStatus.PAYMENT_PROCESSING.name(),
                            "newStatus", OrderStatus.PAYMENT_PENDING.name()))));
            log.info("Uncertain payment queued for reconciliation for orderId={}", orderId);
            return null;
        });
    }

    /**
     * Attempts to refund the order's payment.
     *
     * @return {@code true} if the refund succeeded (or was unnecessary — e.g. no payment, or the
     *         payment service reports it already refunded); {@code false} if the refund failed and
     *         the customer may still be owed money.
     */
    private boolean attemptRefund(Order order) {
        logSagaStep(order, refundPaymentStep.getName(), "STARTED", null, null, null, 0);
        try {
            refundPaymentStep.execute(new SagaContext(order));
            logSagaStep(order, refundPaymentStep.getName(), "SUCCESS", null, null, null, 0);
            return true;
        } catch (SagaStepException e) {
            logSagaStep(order, refundPaymentStep.getName(), "FAILED", null, null, e.getMessage(), 0);
            log.error("Refund FAILED for orderId={} — order will NOT be finalized as CANCELLED. Reason: {}",
                      order.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Holds an order in {@link OrderStatus#REFUND_FAILED} after a failed refund and emits a
     * status-changed event so the unresolved refund is visible to ops (admin dashboard). Idempotent:
     * if the order is already REFUND_FAILED it only logs, avoiding repeated no-op events on each
     * recovery cycle.
     */
    private void markRefundFailed(Order order) {
        if (order.getStatus() == OrderStatus.REFUND_FAILED) {
            log.error("Refund still failing for orderId={} — remains REFUND_FAILED; manual intervention "
                      + "may be required", order.getId());
            return;
        }
        OrderStatus previous = order.getStatus();
        if (!transition(order, previous, OrderStatus.REFUND_FAILED, previous)) {
            log.warn("Could not mark orderId={} REFUND_FAILED: it left {} concurrently", order.getId(), previous);
        }
    }
}
