package com.robomart.order.unit.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.enums.OrderStatus;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSagaOrchestrator - Cancellation")
class OrderSagaOrchestratorCancelTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private SagaAuditLogRepository sagaAuditLogRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ReserveInventoryStep reserveInventoryStep;
    @Mock private ProcessPaymentStep processPaymentStep;
    @Mock private ReleaseInventoryStep releaseInventoryStep;
    @Mock private RefundPaymentStep refundPaymentStep;

    @Captor private ArgumentCaptor<OutboxEvent> outboxCaptor;

    private OrderSagaOrchestrator orchestrator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SagaProperties sagaProperties = new SagaProperties();
        orchestrator = new OrderSagaOrchestrator(
                orderRepository, orderStatusHistoryRepository, outboxEventRepository,
                sagaAuditLogRepository, transactionTemplate, objectMapper,
                sagaProperties, reserveInventoryStep, processPaymentStep,
                releaseInventoryStep, refundPaymentStep);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(sagaAuditLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderStatusHistoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(outboxEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(releaseInventoryStep.getName()).thenReturn("ReleaseInventory");
        lenient().when(refundPaymentStep.getName()).thenReturn("RefundPaymentStep");
    }

    private Order buildPendingOrder() {
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
        order.setItems(new ArrayList<>());
        return order;
    }

    private Order buildConfirmedOrder() {
        Order order = buildPendingOrder();
        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentId("pay-123");
        order.setReservationId("res-456");
        OrderItem item = new OrderItem();
        item.setProductId(10L);
        item.setProductName("Widget");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));
        order.setItems(new ArrayList<>(List.of(item)));
        return order;
    }

    @Nested
    @DisplayName("cancelPendingSaga")
    class CancelPendingSaga {

        @Test
        @DisplayName("shouldCancelPendingOrderWithNoReservation")
        void shouldCancelPendingOrderWithNoReservation() {
            Order order = buildPendingOrder();
            order.setReservationId(null);

            orchestrator.cancelPendingSaga(order, "Changed my mind", "user-1");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).isEqualTo("Changed my mind");
            verify(releaseInventoryStep, never()).compensate(any());
            verify(outboxEventRepository).save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("order_cancelled");
        }

        @Test
        @DisplayName("shouldCancelPendingOrderWithInventoryRelease")
        void shouldCancelPendingOrderWithInventoryRelease() {
            Order order = buildPendingOrder();
            order.setReservationId("res-789");
            doNothing().when(releaseInventoryStep).compensate(any(SagaContext.class));

            orchestrator.cancelPendingSaga(order, "Customer cancelled", "user-1");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(releaseInventoryStep).compensate(any(SagaContext.class));
            verify(outboxEventRepository).save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("order_cancelled");
        }
    }

    @Nested
    @DisplayName("cancelConfirmedSaga")
    class CancelConfirmedSaga {

        @Test
        @DisplayName("shouldCancelConfirmedOrderWithRefundAndRelease")
        void shouldCancelConfirmedOrderWithRefundAndRelease() {
            Order order = buildConfirmedOrder();
            doNothing().when(refundPaymentStep).execute(any(SagaContext.class));
            doNothing().when(releaseInventoryStep).compensate(any(SagaContext.class));

            orchestrator.cancelConfirmedSaga(order, "Customer cancelled", "user-1");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).isEqualTo("Customer cancelled");
            verify(refundPaymentStep).execute(any(SagaContext.class));
            verify(releaseInventoryStep).compensate(any(SagaContext.class));
            verify(outboxEventRepository).save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("order_cancelled");
        }

        @Test
        @DisplayName("shouldCancelOrderEvenIfRefundFails")
        void shouldCancelOrderEvenIfRefundFails() {
            Order order = buildConfirmedOrder();
            doThrow(new SagaStepException("Refund failed", false))
                    .when(refundPaymentStep).execute(any(SagaContext.class));
            doNothing().when(releaseInventoryStep).compensate(any(SagaContext.class));

            // Should NOT throw — best-effort
            orchestrator.cancelConfirmedSaga(order, "Customer cancelled", "user-1");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            // Still releases inventory even when refund failed
            verify(releaseInventoryStep).compensate(any(SagaContext.class));
            // Still publishes cancelled event
            verify(outboxEventRepository).save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("order_cancelled");
        }
    }
}
