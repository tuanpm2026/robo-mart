package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import com.robomart.order.web.OrderDetailResponse;
import com.robomart.order.web.OrderSummaryResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService - getOrdersByUser / getOrderForUser")
class OrderServiceGetTest {

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

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

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

    private OrderItem buildItem(Order order) {
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(1L);
        item.setProductName("Widget");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));
        return item;
    }

    // -----------------------------------------------------------------------
    // getOrdersByUser
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getOrdersByUser with valid userId returns page with correct items")
    void getOrdersByUser_validUserId_returnsCorrectPage() {
        String userId = "user-1";
        Order order = buildOrder(10L, userId, OrderStatus.CONFIRMED);
        Page<Order> orderPage = new PageImpl<>(List.of(order), PageRequest.of(0, 10), 1);

        when(orderRepository.findByUserId(any(String.class), any())).thenReturn(orderPage);
        when(orderItemRepository.countsByOrderIds(List.of(10L)))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 3L}));

        Page<OrderSummaryResponse> result = orderService.getOrdersByUser(userId, 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getContent()).hasSize(1);
        OrderSummaryResponse summary = result.getContent().get(0);
        assertThat(summary.id()).isEqualTo(10L);
        assertThat(summary.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(summary.itemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getOrdersByUser with userId having no orders returns empty page")
    void getOrdersByUser_noOrders_returnsEmptyPage() {
        Page<Order> emptyPage = Page.empty(PageRequest.of(0, 10));
        when(orderRepository.findByUserId(any(String.class), any())).thenReturn(emptyPage);

        Page<OrderSummaryResponse> result = orderService.getOrdersByUser("user-nobody", 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(0L);
        assertThat(result.getContent()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // getOrderForUser
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getOrderForUser with correct owner returns OrderDetailResponse")
    void getOrderForUser_correctOwner_returnsDetail() {
        String userId = "user-1";
        Long orderId = 10L;
        Order order = buildOrder(orderId, userId, OrderStatus.CONFIRMED);
        OrderItem item = buildItem(order);
        order.setItems(List.of(item));

        OrderStatusHistory history = new OrderStatusHistory();
        history.setStatus(OrderStatus.PENDING);
        history.setChangedAt(Instant.now().minusSeconds(60));

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(orderId))
                .thenReturn(List.of(history));

        OrderDetailResponse detail = orderService.getOrderForUser(orderId, userId);

        assertThat(detail.id()).isEqualTo(orderId);
        assertThat(detail.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).productName()).isEqualTo("Widget");
        assertThat(detail.statusHistory()).hasSize(1);
        assertThat(detail.statusHistory().get(0).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("getOrderForUser with orderId belonging to different user throws ResourceNotFoundException")
    void getOrderForUser_differentUser_throwsResourceNotFoundException() {
        Long orderId = 10L;
        Order order = buildOrder(orderId, "user-owner", OrderStatus.CONFIRMED);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.getOrderForUser(orderId, "user-intruder"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(orderId.toString());
    }

    @Test
    @DisplayName("getOrderForUser with non-existent orderId throws ResourceNotFoundException")
    void getOrderForUser_nonExistentOrder_throwsResourceNotFoundException() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderForUser(999L, "user-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
