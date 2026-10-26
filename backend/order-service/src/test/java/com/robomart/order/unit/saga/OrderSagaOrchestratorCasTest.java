package com.robomart.order.unit.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderNotCancellableException;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.saga.steps.ProcessPaymentStep;
import com.robomart.order.saga.steps.RefundPaymentStep;
import com.robomart.order.saga.steps.ReleaseInventoryStep;
import com.robomart.order.saga.steps.ReserveInventoryStep;

import tools.jackson.databind.ObjectMapper;

/** Status transitions are compare-and-set: a saga that loses one must stop, never confirm or charge. */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSagaOrchestrator - compare-and-set transitions")
class OrderSagaOrchestratorCasTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private SagaAuditLogRepository sagaAuditLogRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ReserveInventoryStep reserveInventoryStep;
    @Mock private ProcessPaymentStep processPaymentStep;
    @Mock private ReleaseInventoryStep releaseInventoryStep;
    @Mock private RefundPaymentStep refundPaymentStep;

    private OrderSagaOrchestrator orchestrator;
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final List<String> writesOutsideTransaction = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orchestrator = new OrderSagaOrchestrator(
                orderRepository, orderStatusHistoryRepository, outboxEventRepository,
                sagaAuditLogRepository, transactionTemplate, new ObjectMapper(),
                new SagaProperties(), reserveInventoryStep, processPaymentStep,
                releaseInventoryStep, refundPaymentStep);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            inTransaction.set(true);
            try {
                return callback.doInTransaction(null);
            } finally {
                inTransaction.set(false);
            }
        });
        lenient().when(orderRepository.transitionStatus(any(), any(), any())).thenReturn(true);
        lenient().when(orderStatusHistoryRepository.save(any())).thenAnswer(i -> {
            recordWrite("history");
            return i.getArgument(0);
        });
        lenient().when(outboxEventRepository.save(any())).thenAnswer(i -> {
            recordWrite("outbox");
            return i.getArgument(0);
        });
        lenient().when(sagaAuditLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(reserveInventoryStep.getName()).thenReturn("ReserveInventory");
        lenient().when(processPaymentStep.getName()).thenReturn("ProcessPayment");
        lenient().when(releaseInventoryStep.getName()).thenReturn("ReleaseInventory");
        lenient().when(refundPaymentStep.getName()).thenReturn("RefundPaymentStep");
    }

    private void recordWrite(String what) {
        if (!inTransaction.get()) {
            writesOutsideTransaction.add(what);
        }
    }

    private Order buildOrder(OrderStatus status) {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 5L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId("user-1");
        order.setTotalAmount(new BigDecimal("99.98"));
        order.setStatus(status);
        OrderItem item = new OrderItem();
        item.setProductId(10L);
        item.setProductName("Widget");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));
        order.setItems(new ArrayList<>(List.of(item)));
        return order;
    }

    private void loseTransition(OrderStatus from, OrderStatus to, OrderStatus actual) {
        when(orderRepository.transitionStatus(any(), eq(from), eq(to))).thenReturn(false);
        when(orderRepository.findStatusById(5L)).thenReturn(Optional.of(actual));
    }

    @Test
    @DisplayName("confirming writes status history and the outbox event inside the transition transaction")
    void happyPathWritesHistoryAndOutboxAtomically() {
        Order order = buildOrder(OrderStatus.PENDING);

        orchestrator.executeSaga(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(event.capture());
        assertThat(event.getValue().getPayload()).contains("\"newStatus\":\"CONFIRMED\"");
        assertThat(writesOutsideTransaction).isEmpty();
        InOrder order1 = inOrder(orderRepository);
        order1.verify(orderRepository).transitionStatus(order, OrderStatus.PENDING, OrderStatus.INVENTORY_RESERVING);
        order1.verify(orderRepository).transitionStatus(order, OrderStatus.INVENTORY_RESERVING,
                OrderStatus.PAYMENT_PROCESSING);
        order1.verify(orderRepository).transitionStatus(order, OrderStatus.PAYMENT_PROCESSING, OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("losing the first transition runs no step at all")
    void lostStartRunsNothing() {
        Order order = buildOrder(OrderStatus.PENDING);
        loseTransition(OrderStatus.PENDING, OrderStatus.INVENTORY_RESERVING, OrderStatus.CANCELLED);

        orchestrator.executeSaga(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(reserveInventoryStep, never()).execute(any());
        verify(processPaymentStep, never()).execute(any());
        verify(releaseInventoryStep, never()).compensate(any());
        verify(outboxEventRepository, never()).save(any());
        verify(orderStatusHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("recovery cancelling the order mid-reservation stops the saga before payment")
    void cancelledDuringReservationNeverCharges() {
        Order order = buildOrder(OrderStatus.PENDING);
        loseTransition(OrderStatus.INVENTORY_RESERVING, OrderStatus.PAYMENT_PROCESSING, OrderStatus.CANCELLED);

        orchestrator.executeSaga(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(reserveInventoryStep).execute(any(SagaContext.class));
        verify(processPaymentStep, never()).execute(any());
        verify(orderRepository, never()).transitionStatus(any(), eq(OrderStatus.PAYMENT_PROCESSING),
                eq(OrderStatus.CONFIRMED));
        verify(outboxEventRepository, never()).save(any());
        // The canceller may have released before this reservation landed: release again (idempotent).
        verify(releaseInventoryStep).compensate(any(SagaContext.class));
    }

    @Test
    @DisplayName("losing the CONFIRMED transition publishes no CONFIRMED event and keeps the reservation")
    void lostConfirmPublishesNothing() {
        Order order = buildOrder(OrderStatus.PENDING);
        loseTransition(OrderStatus.PAYMENT_PROCESSING, OrderStatus.CONFIRMED, OrderStatus.PAYMENT_PENDING);

        orchestrator.executeSaga(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        verify(processPaymentStep).execute(any(SagaContext.class));
        verify(outboxEventRepository, never()).save(any());
        verify(releaseInventoryStep, never()).compensate(any());
    }

    @Test
    @DisplayName("payment decline claims INVENTORY_RELEASING before releasing, then cancels")
    void declineClaimsBeforeReleasing() {
        Order order = buildOrder(OrderStatus.PENDING);
        doThrow(new SagaStepException("Payment declined", true))
                .when(processPaymentStep).execute(any(SagaContext.class));

        orchestrator.executeSaga(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        InOrder sequence = inOrder(orderRepository, releaseInventoryStep);
        sequence.verify(orderRepository).transitionStatus(order, OrderStatus.PAYMENT_PROCESSING,
                OrderStatus.INVENTORY_RELEASING);
        sequence.verify(releaseInventoryStep).compensate(any(SagaContext.class));
        sequence.verify(orderRepository).transitionStatus(order, OrderStatus.INVENTORY_RELEASING,
                OrderStatus.CANCELLED);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(event.capture());
        assertThat(event.getValue().getPayload()).contains("\"previousStatus\":\"PAYMENT_PROCESSING\"")
                .contains("\"newStatus\":\"CANCELLED\"");
    }

    @Test
    @DisplayName("payment decline does not release stock when another actor already owns the order")
    void declineLosingClaimDoesNotRelease() {
        Order order = buildOrder(OrderStatus.PENDING);
        doThrow(new SagaStepException("Payment declined", true))
                .when(processPaymentStep).execute(any(SagaContext.class));
        loseTransition(OrderStatus.PAYMENT_PROCESSING, OrderStatus.INVENTORY_RELEASING, OrderStatus.PAYMENT_PENDING);

        orchestrator.executeSaga(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        verify(releaseInventoryStep, never()).compensate(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelling a confirmed order that was concurrently delivered is refused without refunding")
    void cancelConfirmedLosesToDelivery() {
        Order order = buildOrder(OrderStatus.CONFIRMED);
        order.setPaymentId("pay-1");
        loseTransition(OrderStatus.CONFIRMED, OrderStatus.PAYMENT_REFUNDING, OrderStatus.DELIVERED);

        assertThatThrownBy(() -> orchestrator.cancelConfirmedSaga(order, "changed mind", "user-1"))
                .isInstanceOf(OrderNotCancellableException.class)
                .hasMessageContaining("DELIVERED");

        verify(refundPaymentStep, never()).execute(any());
        verify(releaseInventoryStep, never()).compensate(any());
        assertThat(order.getCancellationReason()).isNull();
    }

    @Test
    @DisplayName("cancelling a pending order that the saga already advanced is refused")
    void cancelPendingLosesToSaga() {
        Order order = buildOrder(OrderStatus.PENDING);
        order.setReservationId("res-1");
        loseTransition(OrderStatus.PENDING, OrderStatus.CANCELLED, OrderStatus.INVENTORY_RESERVING);

        assertThatThrownBy(() -> orchestrator.cancelPendingSaga(order, "changed mind", "user-1"))
                .isInstanceOf(OrderNotCancellableException.class);

        verify(releaseInventoryStep, never()).compensate(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("dead-saga recovery claims a stale reservation before releasing it")
    void recoveryClaimsReservingOrderFirst() {
        Order order = buildOrder(OrderStatus.INVENTORY_RESERVING);

        orchestrator.handleDeadSaga(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        InOrder sequence = inOrder(orderRepository, releaseInventoryStep);
        sequence.verify(orderRepository).transitionStatus(order, OrderStatus.INVENTORY_RESERVING,
                OrderStatus.INVENTORY_RELEASING);
        sequence.verify(releaseInventoryStep).compensate(any(SagaContext.class));
        sequence.verify(orderRepository).transitionStatus(order, OrderStatus.INVENTORY_RELEASING,
                OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Recovered from stale state: INVENTORY_RESERVING");
    }

    @Test
    @DisplayName("dead-saga recovery backs off when the saga moved the order in the meantime")
    void recoveryLosingClaimLeavesOrderAlone() {
        Order order = buildOrder(OrderStatus.INVENTORY_RESERVING);
        when(orderRepository.transitionStatus(any(), eq(OrderStatus.INVENTORY_RESERVING),
                eq(OrderStatus.INVENTORY_RELEASING))).thenReturn(false);

        orchestrator.handleDeadSaga(order);

        verify(releaseInventoryStep, never()).compensate(any());
        verify(outboxEventRepository, never()).save(any());
    }
}
