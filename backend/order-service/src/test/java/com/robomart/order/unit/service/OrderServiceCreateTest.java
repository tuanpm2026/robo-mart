package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.service.OrderService;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService.createOrder")
class OrderServiceCreateTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock private OrderSagaOrchestrator orderSagaOrchestrator;
    @Mock private TransactionTemplate transactionTemplate;

    private OrderService orderService;

    private static final List<OrderService.OrderItemRequest> ITEMS = List.of(
            new OrderService.OrderItemRequest("1", "Product A", 2, new BigDecimal("49.99")));

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository, orderItemRepository, orderStatusHistoryRepository,
                orderSagaOrchestrator, transactionTemplate);
    }

    private Order buildOrder(Long id, OrderStatus status) {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setStatus(status);
        order.setItems(List.of());
        return order;
    }

    @Test
    @DisplayName("createOrder executes saga and returns order")
    void createOrder_executesSagaAndReturnsOrder() {
        Order saved = buildOrder(1L, OrderStatus.PENDING);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            // Simulate transaction callback returning saved order
            return saved;
        });
        when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());
        doNothing().when(orderSagaOrchestrator).executeSaga(saved);

        Order result = orderService.createOrder("user-1", ITEMS, "123 Main St");

        assertThat(result).isNotNull();
        verify(orderSagaOrchestrator).executeSaga(saved);
    }

    @Test
    @DisplayName("createOrder returns cancelled order when saga fails (no exception propagated)")
    void createOrder_sagaFailsInternally_returnsCancelledOrder() {
        Order savedOrder = buildOrder(2L, OrderStatus.PENDING);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> savedOrder);
        when(orderItemRepository.findByOrderId(2L)).thenReturn(List.of());
        // Saga modifies order status to CANCELLED internally and returns (no throw)
        org.mockito.Mockito.doAnswer(inv -> {
            Order order = ((Order) inv.getArguments()[0]);
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationReason("Payment declined");
            return null;
        }).when(orderSagaOrchestrator).executeSaga(savedOrder);

        Order result = orderService.createOrder("user-1", ITEMS, "123 Main St");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.getCancellationReason()).isEqualTo("Payment declined");
    }

    @Test
    @DisplayName("createOrder propagates unexpected exception from saga execution")
    void createOrder_unexpectedExceptionFromSaga_propagatesException() {
        Order savedOrder = buildOrder(3L, OrderStatus.PENDING);

        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> savedOrder)
                .thenReturn(null); // second call for cancel
        when(orderItemRepository.findByOrderId(3L)).thenReturn(List.of());
        doThrow(new RuntimeException("Unexpected gRPC error"))
                .when(orderSagaOrchestrator).executeSaga(savedOrder);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> orderService.createOrder("user-1", ITEMS, "123 Main St"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected gRPC error");
    }
}
