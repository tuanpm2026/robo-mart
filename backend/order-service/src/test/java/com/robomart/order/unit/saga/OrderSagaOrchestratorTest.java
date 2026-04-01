package com.robomart.order.unit.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.entity.SagaAuditLog;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.SagaStep;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.saga.steps.ProcessPaymentStep;
import com.robomart.order.saga.steps.RefundPaymentStep;
import com.robomart.order.saga.steps.ReleaseInventoryStep;
import com.robomart.order.saga.steps.ReserveInventoryStep;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSagaOrchestrator")
class OrderSagaOrchestratorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Captor
    private ArgumentCaptor<OrderStatusHistory> historyCaptor;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SagaAuditLogRepository sagaAuditLogRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ReserveInventoryStep reserveInventoryStep;

    @Mock
    private ProcessPaymentStep processPaymentStep;

    @Mock
    private ReleaseInventoryStep releaseInventoryStep;

    @Mock
    private RefundPaymentStep refundPaymentStep;

    private OrderSagaOrchestrator orchestrator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        orchestrator = new OrderSagaOrchestrator(
                orderRepository,
                orderStatusHistoryRepository,
                outboxEventRepository,
                sagaAuditLogRepository,
                transactionTemplate,
                objectMapper,
                reserveInventoryStep,
                processPaymentStep,
                releaseInventoryStep,
                refundPaymentStep);

        // TransactionTemplate executes callback immediately in tests
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        lenient().when(sagaAuditLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
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

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("shouldConfirmOrderWhenAllStepsSucceed")
        void shouldConfirmOrderWhenAllStepsSucceed() {
            Order order = buildOrder();
            doNothing().when(reserveInventoryStep).execute(any(SagaContext.class));
            doNothing().when(processPaymentStep).execute(any(SagaContext.class));

            orchestrator.executeSaga(order);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            verify(reserveInventoryStep).execute(any());
            verify(processPaymentStep).execute(any());
            verify(releaseInventoryStep, never()).compensate(any());
            verify(outboxEventRepository).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("shouldTransitionThroughAllStatesOnSuccess")
        void shouldTransitionThroughAllStatesOnSuccess() {
            Order order = buildOrder();
            doNothing().when(reserveInventoryStep).execute(any(SagaContext.class));
            doNothing().when(processPaymentStep).execute(any(SagaContext.class));

            orchestrator.executeSaga(order);

            // Verify final state is CONFIRMED
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            // Verify 3 status history entries: INVENTORY_RESERVING, PAYMENT_PROCESSING, CONFIRMED
            verify(orderStatusHistoryRepository, times(3)).save(historyCaptor.capture());
            List<OrderStatusHistory> histories = historyCaptor.getAllValues();
            assertThat(histories.get(0).getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVING);
            assertThat(histories.get(1).getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
            assertThat(histories.get(2).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("shouldWriteSagaAuditLogEntriesOnSuccess")
        void shouldWriteSagaAuditLogEntriesOnSuccess() {
            Order order = buildOrder();
            doNothing().when(reserveInventoryStep).execute(any(SagaContext.class));
            doNothing().when(processPaymentStep).execute(any(SagaContext.class));

            orchestrator.executeSaga(order);

            // STARTED + SUCCESS for each forward step = 4 entries
            verify(sagaAuditLogRepository, times(4)).save(any(SagaAuditLog.class));
        }
    }

    @Nested
    @DisplayName("Inventory failure")
    class InventoryFailure {

        @Test
        @DisplayName("shouldCancelOrderWhenInventoryFailsWithoutCompensation")
        void shouldCancelOrderWhenInventoryFailsWithoutCompensation() {
            Order order = buildOrder();
            doThrow(new SagaStepException("Insufficient stock", false))
                    .when(reserveInventoryStep).execute(any(SagaContext.class));

            orchestrator.executeSaga(order);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(processPaymentStep, never()).execute(any());
            verify(releaseInventoryStep, never()).compensate(any());
        }

        @Test
        @DisplayName("shouldPublishStatusChangedEventWhenInventoryFails")
        void shouldPublishStatusChangedEventWhenInventoryFails() {
            Order order = buildOrder();
            doThrow(new SagaStepException("Insufficient stock", false))
                    .when(reserveInventoryStep).execute(any(SagaContext.class));

            orchestrator.executeSaga(order);

            verify(outboxEventRepository).save(any(OutboxEvent.class));
        }
    }

    @Nested
    @DisplayName("Payment failure with compensation")
    class PaymentFailure {

        @Test
        @DisplayName("shouldCompensateAndCancelOrderWhenPaymentFails")
        void shouldCompensateAndCancelOrderWhenPaymentFails() {
            Order order = buildOrder();
            doNothing().when(reserveInventoryStep).execute(any(SagaContext.class));
            doThrow(new SagaStepException("Payment declined", true))
                    .when(processPaymentStep).execute(any(SagaContext.class));
            doNothing().when(releaseInventoryStep).compensate(any(SagaContext.class));

            orchestrator.executeSaga(order);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(reserveInventoryStep).execute(any());
            verify(releaseInventoryStep).compensate(any());
            verify(outboxEventRepository).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("shouldContinueCancellingEvenIfCompensationFails")
        void shouldContinueCancellingEvenIfCompensationFails() {
            Order order = buildOrder();
            doNothing().when(reserveInventoryStep).execute(any(SagaContext.class));
            doThrow(new SagaStepException("Payment declined", true))
                    .when(processPaymentStep).execute(any(SagaContext.class));
            doThrow(new RuntimeException("Release inventory failed"))
                    .when(releaseInventoryStep).compensate(any(SagaContext.class));

            orchestrator.executeSaga(order);

            // Even if compensation fails, order should still be CANCELLED
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
    }
}
