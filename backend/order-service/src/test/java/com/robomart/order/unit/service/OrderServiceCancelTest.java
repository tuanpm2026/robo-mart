package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderNotCancellableException;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.service.OrderService;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService - cancelOrder")
class OrderServiceCancelTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock private OrderSagaOrchestrator orderSagaOrchestrator;
    @Mock private TransactionTemplate transactionTemplate;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository, orderItemRepository, orderStatusHistoryRepository,
                orderSagaOrchestrator, transactionTemplate);
    }

    private Order buildOrder(OrderStatus status) {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 99L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId("user-1");
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(status);
        return order;
    }

    @Test
    @DisplayName("shouldThrowNotFoundForNonExistentOrder")
    void shouldThrowNotFoundForNonExistentOrder() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder(999L, "reason", "user-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED", "CANCELLED",
            "INVENTORY_RESERVING", "PAYMENT_PROCESSING", "PAYMENT_REFUNDING", "INVENTORY_RELEASING"})
    @DisplayName("shouldThrowNotCancellableForNonCancellableState")
    void shouldThrowNotCancellableForNonCancellableState(OrderStatus status) {
        Order order = buildOrder(status);
        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(99L, "reason", "user-1"))
                .isInstanceOf(OrderNotCancellableException.class)
                .hasMessageContaining("cannot be cancelled in state: " + status);
    }

    @Test
    @DisplayName("shouldDelegateToCancelPendingSagaForPendingOrder")
    void shouldDelegateToCancelPendingSagaForPendingOrder() {
        Order order = buildOrder(OrderStatus.PENDING);
        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(99L)).thenReturn(List.of());
        doNothing().when(orderSagaOrchestrator).cancelPendingSaga(any(), any(), any());

        orderService.cancelOrder(99L, "Changed my mind", "user-1");

        verify(orderSagaOrchestrator).cancelPendingSaga(eq(order), eq("Changed my mind"), eq("user-1"));
        verify(orderSagaOrchestrator, never()).cancelConfirmedSaga(any(), any(), any());
    }

    @Test
    @DisplayName("shouldDelegateToCancelConfirmedSagaForConfirmedOrder")
    void shouldDelegateToCancelConfirmedSagaForConfirmedOrder() {
        Order order = buildOrder(OrderStatus.CONFIRMED);
        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(99L)).thenReturn(List.of());
        doNothing().when(orderSagaOrchestrator).cancelConfirmedSaga(any(), any(), any());

        orderService.cancelOrder(99L, "No longer needed", "user-1");

        verify(orderSagaOrchestrator).cancelConfirmedSaga(eq(order), eq("No longer needed"), eq("user-1"));
        verify(orderSagaOrchestrator, never()).cancelPendingSaga(any(), any(), any());
    }

    @Test
    @DisplayName("shouldUseDefaultReasonWhenReasonIsNull")
    void shouldUseDefaultReasonWhenReasonIsNull() {
        Order order = buildOrder(OrderStatus.PENDING);
        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(99L)).thenReturn(List.of());
        doNothing().when(orderSagaOrchestrator).cancelPendingSaga(any(), any(), any());

        orderService.cancelOrder(99L, null, "user-1");

        verify(orderSagaOrchestrator).cancelPendingSaga(
                eq(order), eq("Customer requested cancellation"), eq("user-1"));
    }
}
