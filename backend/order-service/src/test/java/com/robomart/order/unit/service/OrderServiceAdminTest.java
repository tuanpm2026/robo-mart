package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.service.OrderService;
import com.robomart.order.web.AdminOrderDetailResponse;
import com.robomart.order.web.AdminOrderSummaryResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService - Admin Methods")
class OrderServiceAdminTest {

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

    private Order buildOrder(Long id, String userId, OrderStatus status) {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId(userId);
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(status);
        order.setShippingAddress("123 Main St");
        return order;
    }

    // -----------------------------------------------------------------------
    // getAllOrders
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shouldReturnPagedOrdersWhenNoStatusFilter")
    void shouldReturnPagedOrdersWhenNoStatusFilter() {
        Order order1 = buildOrder(1L, "user-a", OrderStatus.CONFIRMED);
        Order order2 = buildOrder(2L, "user-b", OrderStatus.PENDING);
        Page<Order> page = new PageImpl<>(List.of(order1, order2));

        when(orderRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(orderItemRepository.countsByOrderIds(List.of(1L, 2L)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 3L}, new Object[]{2L, 1L}));

        Page<AdminOrderSummaryResponse> result = orderService.getAllOrders(0, 25, null);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).userId()).isEqualTo("user-a");
        assertThat(result.getContent().get(0).itemCount()).isEqualTo(3);
        assertThat(result.getContent().get(1).userId()).isEqualTo("user-b");
        assertThat(result.getContent().get(1).itemCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("shouldReturnPagedOrdersWhenEmptyStatusFilter")
    void shouldReturnPagedOrdersWhenEmptyStatusFilter() {
        Page<Order> emptyPage = Page.empty();
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        Page<AdminOrderSummaryResponse> result = orderService.getAllOrders(0, 25, List.of());

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("shouldFilterByStatusWhenStatusesProvided")
    void shouldFilterByStatusWhenStatusesProvided() {
        Order order = buildOrder(1L, "user-a", OrderStatus.SHIPPED);
        Page<Order> page = new PageImpl<>(List.of(order));
        List<OrderStatus> statuses = List.of(OrderStatus.SHIPPED);

        when(orderRepository.findByStatusIn(eq(statuses), any(Pageable.class))).thenReturn(page);
        when(orderItemRepository.countsByOrderIds(List.of(1L)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 2L}));

        Page<AdminOrderSummaryResponse> result = orderService.getAllOrders(0, 25, statuses);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).status()).isEqualTo(OrderStatus.SHIPPED);
    }

    // -----------------------------------------------------------------------
    // getOrderDetailForAdmin
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shouldReturnAdminOrderDetailWithUserId")
    void shouldReturnAdminOrderDetailWithUserId() {
        Long orderId = 10L;
        Order order = buildOrder(orderId, "user-admin-detail", OrderStatus.CONFIRMED);
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(1L);
        item.setProductName("Widget");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));

        OrderStatusHistory history = new OrderStatusHistory();
        history.setStatus(OrderStatus.PENDING);
        history.setChangedAt(Instant.now().minusSeconds(60));

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(orderId))
                .thenReturn(List.of(history));

        AdminOrderDetailResponse detail = orderService.getOrderDetailForAdmin(orderId);

        assertThat(detail.id()).isEqualTo(orderId);
        assertThat(detail.userId()).isEqualTo("user-admin-detail");
        assertThat(detail.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).productName()).isEqualTo("Widget");
        assertThat(detail.statusHistory()).hasSize(1);
    }

    @Test
    @DisplayName("shouldThrowNotFoundWhenOrderDoesNotExist")
    void shouldThrowNotFoundWhenOrderDoesNotExist() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderDetailForAdmin(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // updateOrderStatus
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shouldUpdateStatusWhenConfirmedToShipped")
    void shouldUpdateStatusWhenConfirmedToShipped() {
        Order order = buildOrder(1L, "user-a", OrderStatus.CONFIRMED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order updated = orderService.updateOrderStatus(1L, OrderStatus.SHIPPED);

        assertThat(updated.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        ArgumentCaptor<OrderStatusHistory> histCaptor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orderStatusHistoryRepository).save(histCaptor.capture());
        assertThat(histCaptor.getValue().getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("shouldUpdateStatusWhenShippedToDelivered")
    void shouldUpdateStatusWhenShippedToDelivered() {
        Order order = buildOrder(2L, "user-b", OrderStatus.SHIPPED);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order updated = orderService.updateOrderStatus(2L, OrderStatus.DELIVERED);

        assertThat(updated.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("shouldRejectInvalidTransitionWhenPendingToShipped")
    void shouldRejectInvalidTransitionWhenPendingToShipped() {
        Order order = buildOrder(3L, "user-c", OrderStatus.PENDING);
        when(orderRepository.findById(3L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateOrderStatus(3L, OrderStatus.SHIPPED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid status transition");
    }

    @Test
    @DisplayName("shouldRejectInvalidTransitionWhenConfirmedToDelivered")
    void shouldRejectInvalidTransitionWhenConfirmedToDelivered() {
        Order order = buildOrder(4L, "user-d", OrderStatus.CONFIRMED);
        when(orderRepository.findById(4L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateOrderStatus(4L, OrderStatus.DELIVERED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid status transition");
    }

    @Test
    @DisplayName("shouldRejectInvalidTransitionWhenCancelledToShipped")
    void shouldRejectInvalidTransitionWhenCancelledToShipped() {
        Order order = buildOrder(5L, "user-e", OrderStatus.CANCELLED);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateOrderStatus(5L, OrderStatus.SHIPPED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid status transition");
    }

    @Test
    @DisplayName("shouldThrowNotFoundWhenUpdatingNonExistentOrder")
    void shouldThrowNotFoundWhenUpdatingNonExistentOrder() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.updateOrderStatus(999L, OrderStatus.SHIPPED))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
