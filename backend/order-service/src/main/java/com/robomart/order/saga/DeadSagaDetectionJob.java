package com.robomart.order.saga;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;

@Component
public class DeadSagaDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(DeadSagaDetectionJob.class);

    // Non-terminal states where a saga could be stuck.
    // PAYMENT_PENDING is intentionally excluded: it is a circuit-breaker hold state, not a dead saga.
    // Orders in PAYMENT_PENDING are awaiting circuit recovery and should not be auto-cancelled.
    private static final List<OrderStatus> STUCK_STATUSES = List.of(
        OrderStatus.INVENTORY_RESERVING,
        OrderStatus.PAYMENT_PROCESSING,
        OrderStatus.PAYMENT_REFUNDING,
        OrderStatus.INVENTORY_RELEASING
    );

    private final OrderRepository orderRepository;
    private final OrderSagaOrchestrator sagaOrchestrator;
    private final SagaProperties sagaProperties;

    public DeadSagaDetectionJob(OrderRepository orderRepository,
                                OrderSagaOrchestrator sagaOrchestrator,
                                SagaProperties sagaProperties) {
        this.orderRepository = orderRepository;
        this.sagaOrchestrator = sagaOrchestrator;
        this.sagaProperties = sagaProperties;
    }

    @Scheduled(
        fixedDelayString = "${saga.dead-saga-detection.check-interval-ms:60000}",
        initialDelayString = "${saga.dead-saga-detection.initial-delay-ms:30000}"
    )
    public void detectAndRecoverDeadSagas() {
        if (!sagaProperties.getDeadSagaDetection().isEnabled()) {
            return;
        }
        Instant cutoff = Instant.now()
            .minus(sagaProperties.getDeadSagaDetection().getStuckThreshold());

        List<Order> stuckOrders = orderRepository.findStuckSagas(STUCK_STATUSES, cutoff);
        if (stuckOrders.isEmpty()) {
            return;
        }

        log.warn("Dead saga detection: {} stuck order(s) found (threshold={})",
                 stuckOrders.size(),
                 sagaProperties.getDeadSagaDetection().getStuckThreshold());

        for (Order order : stuckOrders) {
            try {
                sagaOrchestrator.handleDeadSaga(order);
            } catch (Exception e) {
                log.error("Recovery failed for dead saga orderId={}: {}",
                          order.getId(), e.getMessage(), e);
            }
        }
    }
}
