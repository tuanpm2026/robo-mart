package com.robomart.order.unit.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.SagaAuditLog;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.SagaStepResult;
import com.robomart.order.saga.steps.ProcessPaymentStep;
import com.robomart.order.saga.steps.RefundPaymentStep;
import com.robomart.order.saga.steps.ReleaseInventoryStep;
import com.robomart.order.saga.steps.ReserveInventoryStep;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSagaOrchestrator - Phase B Hardening")
class OrderSagaOrchestratorPhaseBTest {

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
    private SagaProperties sagaProperties;

    @BeforeEach
    void setUp() {
        lenient().when(reserveInventoryStep.execute(any())).thenReturn(SagaStepResult.NONE);
        lenient().when(processPaymentStep.execute(any())).thenReturn(SagaStepResult.NONE);
        sagaProperties = new SagaProperties();

        orchestrator = new OrderSagaOrchestrator(
                orderRepository, orderStatusHistoryRepository, outboxEventRepository,
                sagaAuditLogRepository, transactionTemplate, new ObjectMapper(),
                sagaProperties, reserveInventoryStep, processPaymentStep,
                releaseInventoryStep, refundPaymentStep);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(sagaAuditLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(sagaAuditLogRepository.existsByIdempotencyKeyAndStatus(any(), any()))
                 .thenReturn(false);
        lenient().when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.transitionStatus(any(), any(), any())).thenReturn(true);
        lenient().when(orderStatusHistoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(outboxEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        lenient().when(reserveInventoryStep.getName()).thenReturn("ReserveInventory");
        lenient().when(processPaymentStep.getName()).thenReturn("ProcessPayment");
        lenient().when(releaseInventoryStep.getName()).thenReturn("ReleaseInventory");
        lenient().when(refundPaymentStep.getName()).thenReturn("RefundPaymentStep");
    }

    private Order buildOrder() {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId("user-1");
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(OrderStatus.PENDING);
        OrderItem item = new OrderItem();
        item.setProductId(10L);
        item.setProductName("Widget");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));
        order.setItems(new ArrayList<>(List.of(item)));
        return order;
    }

    @Test
    @DisplayName("stepIsSkippedWhenAlreadySucceeded")
    void stepIsSkippedWhenAlreadySucceeded() {
        Order order = buildOrder();
        when(sagaAuditLogRepository.existsByIdempotencyKeyAndStatus(
            contains(":ReserveInventory"), eq("SUCCESS"))).thenReturn(true);
        when(sagaAuditLogRepository.existsByIdempotencyKeyAndStatus(
            contains(":ProcessPayment"), eq("SUCCESS"))).thenReturn(true);

        orchestrator.executeSaga(order);

        // Both steps were skipped due to idempotency
        verify(reserveInventoryStep, never()).execute(any());
        verify(processPaymentStep, never()).execute(any());
    }

    @Test
    @DisplayName("idempotencyKeyWrittenToAuditLog")
    void idempotencyKeyWrittenToAuditLog() {
        Order order = buildOrder();
        doReturn(SagaStepResult.NONE).when(reserveInventoryStep).execute(any(SagaContext.class));
        doReturn(SagaStepResult.NONE).when(processPaymentStep).execute(any(SagaContext.class));

        orchestrator.executeSaga(order);

        verify(sagaAuditLogRepository, atLeastOnce()).save(
            argThat(l -> l instanceof SagaAuditLog
                && ((SagaAuditLog) l).getIdempotencyKey() != null
                && ((SagaAuditLog) l).getIdempotencyKey().contains(":ReserveInventory")));
    }

    @Test
    @DisplayName("timeoutOnReserveInventoryTriggersCompensationAndCancel")
    void timeoutOnReserveInventoryTriggersCompensationAndCancel() throws Exception {
        Order order = buildOrder();
        org.mockito.Mockito.doThrow(io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException())
                .when(reserveInventoryStep).execute(any(SagaContext.class));
        sagaProperties.getSteps().getTimeouts().put("ReserveInventory", Duration.ofMillis(100));
        doNothing().when(releaseInventoryStep).compensate(any(SagaContext.class));

        orchestrator.executeSaga(order);

        // Order should end up CANCELLED after timeout triggers compensation
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // TIMED_OUT audit entry must be logged
        verify(sagaAuditLogRepository, atLeastOnce()).save(
            argThat(l -> l instanceof SagaAuditLog
                && "TIMED_OUT".equals(((SagaAuditLog) l).getStatus())
                && ((SagaAuditLog) l).getTimeoutAt() != null));
    }

    @Test
    void paymentTimeoutHoldsReservationForSafeReplay() throws Exception {
        Order order = buildOrder();
        order.setReservationId("reserved-before-payment");
        org.mockito.Mockito.doThrow(io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException())
                .when(processPaymentStep).execute(any(SagaContext.class));
        orchestrator.executeSaga(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getReservationId()).isEqualTo("reserved-before-payment");
        assertThat(order.getNextPaymentRetryAt()).isNotNull();
        verify(releaseInventoryStep, never()).compensate(any());
    }

    @Test
    @DisplayName("reservationIdPersistedInStepSuccessTransaction")
    void reservationIdPersistedInStepSuccessTransaction() {
        Order order = buildOrder();
        doReturn(new SagaStepResult("res-persisted-1", null))
                .when(reserveInventoryStep).execute(any(SagaContext.class));
        doReturn(SagaStepResult.NONE).when(processPaymentStep).execute(any(SagaContext.class));

        orchestrator.executeSaga(order);

        // The reservationId is persisted in the step-success path, guarded on the saga still owning
        // the order (status unchanged INVENTORY_RESERVING -> INVENTORY_RESERVING).
        verify(orderRepository).transitionStatus(
            argThat(o -> o instanceof Order && "res-persisted-1".equals(((Order) o).getReservationId())),
            eq(OrderStatus.INVENTORY_RESERVING), eq(OrderStatus.INVENTORY_RESERVING));
    }

    @Test
    @DisplayName("retryCountWrittenToAuditLog")
    void retryCountWrittenToAuditLog() {
        Order order = buildOrder();
        doReturn(SagaStepResult.NONE).when(reserveInventoryStep).execute(any(SagaContext.class));
        doReturn(SagaStepResult.NONE).when(processPaymentStep).execute(any(SagaContext.class));

        orchestrator.executeSaga(order);

        // retryCount should be 0 for fresh executions
        verify(sagaAuditLogRepository, atLeastOnce()).save(
            argThat(l -> l instanceof SagaAuditLog
                && ((SagaAuditLog) l).getRetryCount() == 0));
    }
}
