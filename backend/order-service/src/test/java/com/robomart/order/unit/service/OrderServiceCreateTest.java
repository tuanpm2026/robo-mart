package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.exception.ValidationException;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderProductUnavailableException;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.service.OrderService;
import com.robomart.order.service.ProductPricingService;
import com.robomart.order.service.ProductPricingService.PricedProduct;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService.createOrder")
class OrderServiceCreateTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock private OrderSagaOrchestrator orderSagaOrchestrator;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ProductPricingService productPricingService;

    private OrderService orderService;

    private static final List<OrderService.OrderItemRequest> ITEMS = List.of(
            new OrderService.OrderItemRequest("1", "Product A", 2, new BigDecimal("49.99")));

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository, orderItemRepository, orderStatusHistoryRepository,
                orderSagaOrchestrator, transactionTemplate, productPricingService);
        lenient().when(productPricingService.currentPrices(anyCollection()))
                .thenReturn(Map.of(1L, new PricedProduct("Product A", new BigDecimal("49.99"))));
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

    /** Runs the real creation callback against the mocked repositories. */
    private void runTransactionsForReal() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 7L);
            return order;
        });
    }

    @Test
    @DisplayName("createOrder executes saga and returns order")
    void createOrder_executesSagaAndReturnsOrder() {
        Order saved = buildOrder(1L, OrderStatus.PENDING);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            // Simulate transaction callback returning saved order
            return saved;
        });
        doNothing().when(orderSagaOrchestrator).executeSaga(saved);

        Order result = orderService.createOrder("user-1", ITEMS, "123 Main St", null);

        assertThat(result).isNotNull();
        verify(orderSagaOrchestrator).executeSaga(saved);
    }

    @Test
    @DisplayName("createOrder returns cancelled order when saga fails (no exception propagated)")
    void createOrder_sagaFailsInternally_returnsCancelledOrder() {
        Order savedOrder = buildOrder(2L, OrderStatus.PENDING);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> savedOrder);
        // Saga modifies order status to CANCELLED internally and returns (no throw)
        org.mockito.Mockito.doAnswer(inv -> {
            Order order = ((Order) inv.getArguments()[0]);
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationReason("Payment declined");
            return null;
        }).when(orderSagaOrchestrator).executeSaga(savedOrder);

        Order result = orderService.createOrder("user-1", ITEMS, "123 Main St", null);

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
        doThrow(new RuntimeException("Unexpected gRPC error"))
                .when(orderSagaOrchestrator).executeSaga(savedOrder);

        assertThatThrownBy(() -> orderService.createOrder("user-1", ITEMS, "123 Main St", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected gRPC error");
    }

    @Test
    @DisplayName("prices every line from product-service and ignores client-supplied name and price")
    void createOrder_ignoresClientPriceAndName() {
        runTransactionsForReal();
        when(productPricingService.currentPrices(List.of(1L, 2L, 1L))).thenReturn(Map.of(
                1L, new PricedProduct("Real Robot", new BigDecimal("150.00")),
                2L, new PricedProduct("Real Arm", new BigDecimal("20.50"))));
        List<OrderService.OrderItemRequest> tampered = List.of(
                new OrderService.OrderItemRequest("1", "Free Robot", 2, new BigDecimal("0.01")),
                new OrderService.OrderItemRequest("2", null, 3, null),
                new OrderService.OrderItemRequest("1", "Again", 1, new BigDecimal("0.01")));

        Order order = orderService.createOrder("user-1", tampered, "123 Main St", null);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("511.50");
        ArgumentCaptor<OrderItem> items = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository, org.mockito.Mockito.times(3)).save(items.capture());
        assertThat(items.getAllValues()).extracting(OrderItem::getProductName)
                .containsExactly("Real Robot", "Real Arm", "Real Robot");
        assertThat(items.getAllValues()).extracting(OrderItem::getUnitPrice)
                .containsExactly(new BigDecimal("150.00"), new BigDecimal("20.50"), new BigDecimal("150.00"));
        assertThat(items.getAllValues()).extracting(OrderItem::getSubtotal)
                .containsExactly(new BigDecimal("300.00"), new BigDecimal("61.50"), new BigDecimal("150.00"));
        assertThat(order.getIdempotencyKey()).isNull();
        verify(orderSagaOrchestrator).executeSaga(order);
    }

    @Test
    @DisplayName("rejects unknown or inactive products before creating anything")
    void createOrder_unknownProduct_rejectedWithoutPersisting() {
        when(productPricingService.currentPrices(anyCollection()))
                .thenThrow(new OrderProductUnavailableException("Product 1 is not available"));

        assertThatThrownBy(() -> orderService.createOrder("user-1", ITEMS, "123 Main St", null))
                .isInstanceOf(OrderProductUnavailableException.class);

        verifyNoInteractions(transactionTemplate, orderSagaOrchestrator);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects non-positive quantities and malformed product ids")
    void createOrder_invalidLines_rejected() {
        assertThatThrownBy(() -> orderService.createOrder("user-1",
                List.of(new OrderService.OrderItemRequest("1", null, 0, null)), "addr", null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> orderService.createOrder("user-1",
                List.of(new OrderService.OrderItemRequest("abc", null, 1, null)), "addr", null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> orderService.createOrder("user-1", ITEMS, "addr", "k".repeat(256)))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(productPricingService, orderSagaOrchestrator);
    }

    @Test
    @DisplayName("replayed idempotency key returns the original order without pricing or a second saga")
    void createOrder_replayedKey_returnsExistingOrder() {
        Order existing = buildOrder(42L, OrderStatus.CONFIRMED);
        when(orderRepository.findByUserIdAndIdempotencyKey("user-1", "key-1")).thenReturn(Optional.of(existing));

        Order result = orderService.createOrder("user-1", ITEMS, "123 Main St", "  key-1 ");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(productPricingService, transactionTemplate, orderSagaOrchestrator);
    }

    @Test
    @DisplayName("first use of an idempotency key stores it on the new order")
    void createOrder_newKey_persistedOnOrder() {
        runTransactionsForReal();
        when(orderRepository.findByUserIdAndIdempotencyKey("user-1", "key-2")).thenReturn(Optional.empty());

        Order order = orderService.createOrder("user-1", ITEMS, "123 Main St", "key-2");

        assertThat(order.getIdempotencyKey()).isEqualTo("key-2");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("99.98");
        verify(orderSagaOrchestrator).executeSaga(order);
    }

    @Test
    @DisplayName("losing the concurrent insert race returns the winner's order and runs no saga")
    void createOrder_concurrentDuplicateKey_returnsWinner() {
        Order winner = buildOrder(43L, OrderStatus.INVENTORY_RESERVING);
        when(orderRepository.findByUserIdAndIdempotencyKey("user-1", "key-3"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(transactionTemplate.execute(any()))
                .thenThrow(new DataIntegrityViolationException("uq_orders_user_idempotency_key"));

        Order result = orderService.createOrder("user-1", ITEMS, "123 Main St", "key-3");

        assertThat(result).isSameAs(winner);
        verifyNoInteractions(orderSagaOrchestrator);
    }

    @Test
    @DisplayName("integrity violations without an idempotency key are not swallowed")
    void createOrder_integrityViolationWithoutKey_propagates() {
        when(transactionTemplate.execute(any())).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> orderService.createOrder("user-1", ITEMS, "123 Main St", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(orderSagaOrchestrator);
    }
}
