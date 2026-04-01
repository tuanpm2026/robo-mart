package com.robomart.order.saga;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final List<SagaStep> forwardSteps;
    private final ReleaseInventoryStep releaseInventoryStep;

    public OrderSagaOrchestrator(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            SagaAuditLogRepository sagaAuditLogRepository,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            ReserveInventoryStep reserveInventoryStep,
            ProcessPaymentStep processPaymentStep,
            ReleaseInventoryStep releaseInventoryStep) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.sagaAuditLogRepository = sagaAuditLogRepository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.forwardSteps = List.of(reserveInventoryStep, processPaymentStep);
        this.releaseInventoryStep = releaseInventoryStep;
    }

    public void executeSaga(Order order) {
        String sagaId = order.getId().toString();
        SagaContext context = new SagaContext(order);

        OrderStatus[] stepTargetStates = {OrderStatus.INVENTORY_RESERVING, OrderStatus.PAYMENT_PROCESSING};

        for (int i = 0; i < forwardSteps.size(); i++) {
            SagaStep step = forwardSteps.get(i);
            OrderStatus targetState = stepTargetStates[i];
            OrderStatus previousStatus = order.getStatus();

            updateOrderStatus(order, targetState);
            logSagaStep(sagaId, sagaId, step.getName(), "STARTED", null, null, null);

            try {
                step.execute(context);
                logSagaStep(sagaId, sagaId, step.getName(), "SUCCESS", null, null, null);
            } catch (SagaStepException e) {
                logSagaStep(sagaId, sagaId, step.getName(), "FAILED", null, null, e.getMessage());
                if (e.isShouldCompensate()) {
                    runCompensation(context, sagaId);
                }
                updateOrderStatus(order, OrderStatus.CANCELLED);
                publishStatusChangedEvent(order, targetState);
                log.warn("Saga failed at step={} for sagaId={}, cancellationReason={}", step.getName(), sagaId, order.getCancellationReason());
                return;
            }
        }

        // All forward steps succeeded
        updateOrderStatus(order, OrderStatus.CONFIRMED);
        publishStatusChangedEvent(order, OrderStatus.PAYMENT_PROCESSING);
        log.info("Saga completed successfully for sagaId={}", sagaId);
    }

    private void runCompensation(SagaContext context, String sagaId) {
        Order order = context.getOrder();
        logSagaStep(sagaId, sagaId, releaseInventoryStep.getName(), "COMPENSATING", null, null, null);
        try {
            releaseInventoryStep.compensate(context);
            logSagaStep(sagaId, sagaId, releaseInventoryStep.getName(), "COMPENSATED", null, null, null);
        } catch (Exception e) {
            logSagaStep(sagaId, sagaId, releaseInventoryStep.getName(), "COMPENSATION_FAILED", null, null, e.getMessage());
            log.error("Compensation failed for sagaId={}: {}", sagaId, e.getMessage(), e);
        }
    }

    private void updateOrderStatus(Order order, OrderStatus newStatus) {
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
                log.error("Failed to create outbox event for orderId={}: {}", order.getId(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    private void logSagaStep(String sagaId, String orderId, String stepName, String stepStatus,
            String request, String response, String error) {
        try {
            SagaAuditLog entry = new SagaAuditLog();
            entry.setSagaId(sagaId);
            entry.setOrderId(orderId);
            entry.setStepName(stepName);
            entry.setStatus(stepStatus);
            entry.setRequest(request);
            entry.setResponse(response);
            entry.setError(error);
            entry.setExecutedAt(Instant.now());
            sagaAuditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write saga audit log for sagaId={}, step={}: {}", sagaId, stepName, e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleSagas() {
        log.info("Checking for stale sagas to recover...");
        List<Order> staleOrders = orderRepository.findByStatusIn(
                List.of(OrderStatus.INVENTORY_RESERVING, OrderStatus.PAYMENT_PROCESSING));

        if (staleOrders.isEmpty()) {
            log.info("No stale sagas found.");
            return;
        }

        log.warn("Found {} stale saga(s) to recover", staleOrders.size());
        for (Order order : staleOrders) {
            log.warn("Recovering stale saga for orderId={}, status={}", order.getId(), order.getStatus());
            logSagaStep(order.getId().toString(), order.getId().toString(), "RECOVERY", "STARTED", null, null, null);
            try {
                String staleStatus = order.getStatus().name();
                OrderStatus previousStatus = order.getStatus();
                SagaContext context = new SagaContext(order);
                order.setCancellationReason("Recovered from stale state: " + staleStatus);
                runCompensation(context, order.getId().toString());
                updateOrderStatus(order, OrderStatus.CANCELLED);
                publishStatusChangedEvent(order, previousStatus);
                logSagaStep(order.getId().toString(), order.getId().toString(), "RECOVERY", "COMPENSATED", null, null, null);
            } catch (Exception e) {
                log.error("Recovery failed for orderId={}: {}", order.getId(), e.getMessage(), e);
                logSagaStep(order.getId().toString(), order.getId().toString(), "RECOVERY", "FAILED", null, null, e.getMessage());
            }
        }
    }
}
