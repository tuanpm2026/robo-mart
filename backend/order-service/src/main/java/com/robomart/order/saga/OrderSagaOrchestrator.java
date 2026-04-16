package com.robomart.order.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.entity.SagaAuditLog;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.saga.steps.ProcessPaymentStep;
import com.robomart.order.saga.steps.RefundPaymentStep;
import com.robomart.order.saga.steps.ReleaseInventoryStep;
import com.robomart.order.saga.steps.ReserveInventoryStep;

import tools.jackson.databind.ObjectMapper;

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

    private final ExecutorService stepExecutor = Executors.newVirtualThreadPerTaskExecutor();

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

    @PreDestroy
    public void shutdownStepExecutor() {
        stepExecutor.shutdown();
        try {
            if (!stepExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                stepExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            stepExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void executeSaga(Order order) {
        String sagaId = order.getId().toString();
        SagaContext context = new SagaContext(order);

        OrderStatus[] stepTargetStates = {OrderStatus.INVENTORY_RESERVING, OrderStatus.PAYMENT_PROCESSING};

        for (int i = 0; i < forwardSteps.size(); i++) {
            SagaStep step = forwardSteps.get(i);
            OrderStatus targetState = stepTargetStates[i];

            updateOrderStatus(order, targetState);

            try {
                executeStep(step, context, 0);
            } catch (SagaStepException e) {
                if (e.isShouldHoldAsPending()) {
                    updateOrderStatus(order, OrderStatus.PAYMENT_PENDING);
                    publishStatusChangedEvent(order, targetState);
                    log.info("Order held as PAYMENT_PENDING for orderId={}", sagaId);
                    return;
                }
                if (e.isShouldCompensate()) {
                    runCompensation(context, sagaId);
                }
                updateOrderStatus(order, OrderStatus.CANCELLED);
                publishStatusChangedEvent(order, targetState);
                log.warn("Saga failed at step={} for sagaId={}, cancellationReason={}",
                        step.getName(), sagaId, order.getCancellationReason());
                return;
            }
        }

        updateOrderStatus(order, OrderStatus.CONFIRMED);
        publishStatusChangedEvent(order, OrderStatus.PAYMENT_PROCESSING);
        log.info("Saga completed successfully for sagaId={}", sagaId);
    }

    /**
     * Executes a saga step with idempotency check and configurable timeout.
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

        Duration timeout = sagaProperties.getSteps().getTimeouts()
            .getOrDefault(step.getName(), sagaProperties.getSteps().getDefaultTimeout());

        logSagaStep(context.getOrder(), step.getName(), "STARTED",
                    idempotencyKey, null, null, retryCount);

        CompletableFuture<Void> future =
            CompletableFuture.runAsync(() -> step.execute(context), stepExecutor);

        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            logSagaStep(context.getOrder(), step.getName(), "SUCCESS",
                        idempotencyKey, null, null, retryCount);

        } catch (TimeoutException e) {
            future.cancel(true);
            logSagaStep(context.getOrder(), step.getName(), "TIMED_OUT",
                        idempotencyKey, Instant.now(),
                        "Step timed out after " + timeout.toSeconds() + "s", retryCount);
            throw new SagaStepException(
                "Saga step " + step.getName() + " timed out after " + timeout.toSeconds() + "s",
                null, true, false);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            logSagaStep(context.getOrder(), step.getName(), "FAILED",
                        idempotencyKey, null,
                        cause != null ? cause.getMessage() : e.getMessage(), retryCount);
            if (cause instanceof SagaStepException sse) {
                throw sse;
            }
            throw new SagaStepException(
                cause != null ? cause.getMessage() : "Step execution failed",
                cause, true, false);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SagaStepException("Step execution interrupted", e, true, false);
        }
    }

    private void runCompensation(SagaContext context, String sagaId) {
        Order order = context.getOrder();
        logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATING", null, null, null, 0);
        try {
            releaseInventoryStep.compensate(context);
            logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATED", null, null, null, 0);
        } catch (Exception e) {
            logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATION_FAILED",
                        null, null, e.getMessage(), 0);
            log.error("Compensation failed for sagaId={}: {}", sagaId, e.getMessage(), e);
        }
    }

    private void updateOrderStatus(Order order, OrderStatus newStatus) {
        try {
            transactionTemplate.execute(status -> {
                order.setStatus(newStatus);
                Order saved = orderRepository.saveAndFlush(order);
                order.setVersion(saved.getVersion());

                OrderStatusHistory history = new OrderStatusHistory();
                history.setOrder(order);
                history.setStatus(newStatus);
                history.setChangedAt(Instant.now());
                orderStatusHistoryRepository.save(history);
                return null;
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            Order reloaded = orderRepository.findById(order.getId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + order.getId()));
            log.warn("Optimistic lock conflict on order {} — current: {}, attempted: {}",
                     order.getId(), reloaded.getStatus(), newStatus);
            if (!reloaded.getStatus().isTerminal()) {
                throw e;
            }
        }
    }

    private void publishStatusChangedEvent(Order order, OrderStatus previousStatus) {
        transactionTemplate.execute(status -> {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("orderId", order.getId().toString());
                payload.put("previousStatus", previousStatus.name());
                payload.put("newStatus", order.getStatus().name());

                OutboxEvent event = new OutboxEvent(
                        "Order",
                        order.getId().toString(),
                        "order_status_changed",
                        objectMapper.writeValueAsString(payload));
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to create outbox event for orderId={}: {}",
                          order.getId(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    private void logSagaStep(Order order, String stepName, String status,
            String idempotencyKey, Instant timeoutAt, String error, int retryCount) {
        try {
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
            sagaAuditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write saga audit log for orderId={}, step={}: {}",
                      order.getId(), stepName, e.getMessage());
        }
    }

    public void cancelPendingSaga(Order order, String reason, String cancelledBy) {
        if (order.getReservationId() != null) {
            logSagaStep(order, releaseInventoryStep.getName(), "STARTED", null, null, null, 0);
            try {
                releaseInventoryStep.compensate(new SagaContext(order));
                logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATED", null, null, null, 0);
            } catch (Exception e) {
                logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATION_FAILED",
                            null, null, e.getMessage(), 0);
                log.error("Failed to release inventory during pending cancellation for orderId={}: {}",
                          order.getId(), e.getMessage(), e);
            }
        }

        finalizeCancellation(order, reason, cancelledBy);
        log.info("Pending order cancelled for orderId={}", order.getId());
    }

    public void cancelConfirmedSaga(Order order, String reason, String cancelledBy) {
        updateOrderStatus(order, OrderStatus.PAYMENT_REFUNDING);
        logSagaStep(order, refundPaymentStep.getName(), "STARTED", null, null, null, 0);
        try {
            refundPaymentStep.execute(new SagaContext(order));
            logSagaStep(order, refundPaymentStep.getName(), "SUCCESS", null, null, null, 0);
        } catch (SagaStepException e) {
            logSagaStep(order, refundPaymentStep.getName(), "FAILED", null, null, e.getMessage(), 0);
            log.error("Refund failed for orderId={}, continuing with cancellation: {}",
                      order.getId(), e.getMessage());
        }

        updateOrderStatus(order, OrderStatus.INVENTORY_RELEASING);
        logSagaStep(order, releaseInventoryStep.getName(), "STARTED", null, null, null, 0);
        try {
            releaseInventoryStep.compensate(new SagaContext(order));
            logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATED", null, null, null, 0);
        } catch (Exception e) {
            logSagaStep(order, releaseInventoryStep.getName(), "COMPENSATION_FAILED",
                        null, null, e.getMessage(), 0);
            log.error("Failed to release inventory during confirmed cancellation for orderId={}: {}",
                      order.getId(), e.getMessage(), e);
        }

        finalizeCancellation(order, reason, cancelledBy);
        log.info("Confirmed order cancelled for orderId={}", order.getId());
    }

    private void finalizeCancellation(Order order, String reason, String cancelledBy) {
        transactionTemplate.execute(status -> {
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationReason(reason);
            Order saved = orderRepository.saveAndFlush(order);
            order.setVersion(saved.getVersion());

            OrderStatusHistory history = new OrderStatusHistory();
            history.setOrder(order);
            history.setStatus(OrderStatus.CANCELLED);
            history.setChangedAt(Instant.now());
            orderStatusHistoryRepository.save(history);

            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("orderId", order.getId().toString());
                payload.put("reason", reason);
                payload.put("cancelledBy", cancelledBy);
                OutboxEvent event = new OutboxEvent(
                        "Order",
                        order.getId().toString(),
                        "order_cancelled",
                        objectMapper.writeValueAsString(payload));
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to create order_cancelled outbox event for orderId={}: {}",
                          order.getId(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Triggers compensation for a stuck/dead saga detected by scheduled job (AC3, AC5).
     */
    public void handleDeadSaga(Order order) {
        log.warn("Dead saga detected: orderId={}, status={}", order.getId(), order.getStatus());
        logSagaStep(order, "DEAD_SAGA_DETECTION", "RECOVERY",
                    null, null,
                    "Stuck in " + order.getStatus() + " — triggering compensation", 0);
        recoverSingleSaga(order);
    }

    private void recoverSingleSaga(Order order) {
        String sagaId = order.getId().toString();
        String staleStatus = order.getStatus().name();
        String recoveryReason = "Recovered from stale state: " + staleStatus;

        if (order.getStatus() == OrderStatus.PAYMENT_REFUNDING) {
            logSagaStep(order, refundPaymentStep.getName(), "STARTED", null, null, null, 0);
            try {
                refundPaymentStep.execute(new SagaContext(order));
                logSagaStep(order, refundPaymentStep.getName(), "SUCCESS", null, null, null, 0);
            } catch (SagaStepException e) {
                logSagaStep(order, refundPaymentStep.getName(), "FAILED", null, null, e.getMessage(), 0);
                log.error("Refund failed during stale saga recovery for orderId={}: {}",
                          order.getId(), e.getMessage());
            }
            runCompensation(new SagaContext(order), sagaId);
            finalizeCancellation(order, recoveryReason, "system");
        } else if (order.getStatus() == OrderStatus.INVENTORY_RELEASING) {
            runCompensation(new SagaContext(order), sagaId);
            finalizeCancellation(order, recoveryReason, "system");
        } else {
            order.setCancellationReason(recoveryReason);
            runCompensation(new SagaContext(order), sagaId);
            finalizeCancellation(order, recoveryReason, "system");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleSagas() {
        log.info("Checking for stale sagas to recover...");
        List<Order> staleOrders = orderRepository.findByStatusIn(
                List.of(OrderStatus.INVENTORY_RESERVING, OrderStatus.PAYMENT_PROCESSING,
                        OrderStatus.PAYMENT_REFUNDING, OrderStatus.INVENTORY_RELEASING));

        if (staleOrders.isEmpty()) {
            log.info("No stale sagas found.");
            return;
        }

        log.warn("Found {} stale saga(s) to recover", staleOrders.size());
        for (Order order : staleOrders) {
            log.warn("Recovering stale saga for orderId={}, status={}", order.getId(), order.getStatus());
            logSagaStep(order, "RECOVERY", "STARTED", null, null, null, 0);
            try {
                recoverSingleSaga(order);
                logSagaStep(order, "RECOVERY", "COMPENSATED", null, null, null, 0);
            } catch (Exception e) {
                log.error("Recovery failed for orderId={}: {}", order.getId(), e.getMessage(), e);
                logSagaStep(order, "RECOVERY", "FAILED", null, null, e.getMessage(), 0);
            }
        }
    }
}
