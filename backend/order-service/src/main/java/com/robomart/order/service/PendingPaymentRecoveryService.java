package com.robomart.order.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.entity.SagaAuditLog;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.grpc.PaymentBusinessException;
import com.robomart.order.grpc.PaymentGrpcClient;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.saga.RecoveryPhase;
import com.robomart.order.saga.SagaRecoveryPolicy;
import com.robomart.order.saga.steps.ReleaseInventoryStep;
import com.robomart.proto.common.Money;
import com.robomart.proto.payment.ProcessPaymentRequest;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import tools.jackson.databind.ObjectMapper;

/** Durable continuation of the payment step, without reserving stock a second time. */
@Service
public class PendingPaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentRecoveryService.class);

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final OutboxEventRepository outbox;
    private final SagaAuditLogRepository audit;
    private final PaymentGrpcClient payment;
    private final ReleaseInventoryStep releaseInventory;
    private final SagaProperties properties;
    private final ObjectMapper mapper;
    private final SagaRecoveryPolicy recoveryPolicy;

    public PendingPaymentRecoveryService(OrderRepository orders, OrderStatusHistoryRepository history,
            OutboxEventRepository outbox, SagaAuditLogRepository audit, PaymentGrpcClient payment,
            ReleaseInventoryStep releaseInventory, SagaProperties properties, ObjectMapper mapper,
            SagaRecoveryPolicy recoveryPolicy) {
        this.orders = orders;
        this.history = history;
        this.outbox = outbox;
        this.audit = audit;
        this.payment = payment;
        this.releaseInventory = releaseInventory;
        this.properties = properties;
        this.mapper = mapper;
        this.recoveryPolicy = recoveryPolicy;
    }

    /**
     * The row lock spans the bounded RPC and atomic state/outbox commit. A crash rolls the order
     * back to PAYMENT_PENDING; the next worker replays the same durable Payment idempotency key.
     * SKIP LOCKED lets other replicas process different orders without lease-expiry races.
     */
    @Transactional(timeout = 30)
    public boolean resumeNextPayment() {
        var candidate = orders.findNextPendingPaymentForUpdate(Instant.now());
        if (candidate.isEmpty()) {
            return false;
        }
        Order order = candidate.get();
        if (!recoveryPolicy.tryBegin(order, RecoveryPhase.PAYMENT)) {
            return true;
        }
        order.setPaymentRetryCount(order.getPaymentRetryCount() + 1);
        order.setNextPaymentRetryAt(Instant.now().plus(properties.getPaymentRecovery().getRetryDelay()));

        var request = ProcessPaymentRequest.newBuilder()
                .setOrderId(order.getId().toString()).setUserId(order.getUserId())
                .setAmount(Money.newBuilder().setCurrency("USD").setAmount(order.getTotalAmount().toPlainString()))
                .setIdempotencyKey(order.getId().toString()).build();
        try {
            var response = payment.resumePayment(request);
            if (!response.getSuccess()) {
                throw new PaymentBusinessException("Payment declined", null);
            }
            if (response.getPaymentId().isBlank()) {
                throw new IllegalStateException("Payment returned an incomplete result");
            }
            order.setPaymentId(response.getPaymentId());
            order.setCancellationReason(null);
        } catch (PaymentBusinessException declined) {
            // Only a definitive rejection can cancel. Unavailable compensation stays retryable.
            try {
                releaseInventory.releaseForRecovery(order);
            } catch (RuntimeException unavailable) {
                recoveryPolicy.failed(order.getId(), RecoveryPhase.PAYMENT, "Declined payment; release unavailable");
                recordAttempt(order, "FAILED", "Declined payment; inventory release unavailable");
                log.warn("Pending order {} awaits inventory release after payment decline", order.getId());
                return true;
            }
            order.setCancellationReason("Payment declined");
            recoveryPolicy.completed(order.getId(), RecoveryPhase.PAYMENT);
            finish(order, OrderStatus.CANCELLED);
            recordAttempt(order, "FAILED", "Payment declined");
            return true;
        } catch (RuntimeException uncertain) {
            // A denied breaker probe sent no RPC. Recheck promptly instead of adding the full
            // provider backoff after the breaker itself has already waited 30 seconds.
            if (uncertain.getCause() instanceof CallNotPermittedException) {
                order.setNextPaymentRetryAt(Instant.now().plus(
                        properties.getPaymentRecovery().getRetryDelay().compareTo(Duration.ofSeconds(5)) < 0
                                ? properties.getPaymentRecovery().getRetryDelay() : Duration.ofSeconds(5)));
            }
            // A timeout can hide a completed charge. Never release stock or generate a new key.
            recoveryPolicy.failed(order.getId(), RecoveryPhase.PAYMENT, "Payment unavailable or result uncertain");
            recordAttempt(order, "FAILED", "Payment unavailable or result uncertain");
            log.info("Payment recovery deferred for order {} (attempt {})",
                    order.getId(), order.getPaymentRetryCount());
            return true;
        }

        String key = order.getId() + ":ProcessPayment";
        if (!audit.existsByIdempotencyKeyAndStatus(key, "SUCCESS")) {
            recordAttempt(order, "SUCCESS", null);
        }
        recoveryPolicy.completed(order.getId(), RecoveryPhase.PAYMENT);
        finish(order, OrderStatus.CONFIRMED);
        log.info("Pending payment resumed for order {}", order.getId());
        return true;
    }

    private void finish(Order order, OrderStatus status) {
        order.setStatus(status);
        order.setNextPaymentRetryAt(null);
        var entry = new OrderStatusHistory();
        entry.setOrder(order);
        entry.setStatus(status);
        entry.setChangedAt(Instant.now());
        history.save(entry);
        outbox.save(new OutboxEvent("Order", order.getId().toString(), "order_status_changed",
                mapper.writeValueAsString(Map.of("orderId", order.getId().toString(),
                        "previousStatus", OrderStatus.PAYMENT_PENDING.name(), "newStatus", status.name()))));
    }

    private void recordAttempt(Order order, String status, String error) {
        var entry = new SagaAuditLog();
        entry.setSagaId(order.getId().toString());
        entry.setOrderId(order.getId().toString());
        entry.setStepName("ProcessPayment");
        entry.setIdempotencyKey(order.getId() + ":ProcessPayment");
        entry.setStatus(status);
        entry.setRetryCount(order.getPaymentRetryCount());
        entry.setExecutedAt(Instant.now());
        entry.setError(error);
        audit.save(entry);
    }
}
