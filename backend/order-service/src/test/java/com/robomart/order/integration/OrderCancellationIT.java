package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderNotCancellableException;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.service.OrderService;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;
import com.robomart.test.PostgresContainerConfig;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
@DisplayName("OrderCancellation Integration Tests")
class OrderCancellationIT {

    @MockitoBean
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @MockitoBean
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        Mockito.reset(inventoryStub, paymentStub);
    }

    /**
     * Persists an order directly in a given state, bypassing the saga.
     */
    private Order persistOrderInState(OrderStatus status, String reservationId, String paymentId) {
        return transactionTemplate.execute(txStatus -> {
            Order order = new Order();
            order.setUserId("user-cancel-test");
            order.setTotalAmount(new BigDecimal("99.99"));
            order.setStatus(status);
            order.setShippingAddress("123 Cancel St, Test, TX, 75001, US");
            order.setReservationId(reservationId);
            order.setPaymentId(paymentId);
            Order saved = orderRepository.save(order);

            OrderItem item = new OrderItem();
            item.setOrder(saved);
            item.setProductId(10L);
            item.setProductName("Widget");
            item.setQuantity(2);
            item.setUnitPrice(new BigDecimal("49.99"));
            item.setSubtotal(new BigDecimal("99.98"));
            orderItemRepository.save(item);

            return saved;
        });
    }

    @Test
    @DisplayName("shouldCancelPendingOrderWithInventoryRelease")
    void shouldCancelPendingOrderWithInventoryRelease() {
        Order order = persistOrderInState(OrderStatus.PENDING, "res-cancel-001", null);

        when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());

        orderService.cancelOrder(order.getId(), "Changed my mind", "user-cancel-test");

        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(persisted.getCancellationReason()).isEqualTo("Changed my mind");

        verify(inventoryStub).releaseInventory(any(ReleaseInventoryRequest.class));

        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        boolean hasCancelledEvent = events.stream()
                .anyMatch(e -> e.getEventType().equals("order_cancelled")
                        && e.getAggregateId().equals(order.getId().toString()));
        assertThat(hasCancelledEvent).isTrue();
    }

    @Test
    @DisplayName("shouldCancelConfirmedOrderWithRefundAndRelease")
    void shouldCancelConfirmedOrderWithRefundAndRelease() {
        Order order = persistOrderInState(OrderStatus.CONFIRMED, "res-conf-001", "pay-conf-001");

        when(paymentStub.refundPayment(any(RefundPaymentRequest.class)))
                .thenReturn(RefundPaymentResponse.newBuilder()
                        .setSuccess(true)
                        .setRefundTransactionId("refund-txn-001")
                        .build());
        when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());

        orderService.cancelOrder(order.getId(), "No longer needed", "user-cancel-test");

        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        verify(paymentStub).refundPayment(any(RefundPaymentRequest.class));
        verify(inventoryStub).releaseInventory(any(ReleaseInventoryRequest.class));

        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        boolean hasCancelledEvent = events.stream()
                .anyMatch(e -> e.getEventType().equals("order_cancelled")
                        && e.getAggregateId().equals(order.getId().toString()));
        assertThat(hasCancelledEvent).isTrue();
    }

    @Test
    @DisplayName("shouldRejectCancellationForShippedOrder")
    void shouldRejectCancellationForShippedOrder() {
        Order order = persistOrderInState(OrderStatus.SHIPPED, "res-ship-001", "pay-ship-001");

        assertThatThrownBy(() -> orderService.cancelOrder(order.getId(), "reason", "user-cancel-test"))
                .isInstanceOf(OrderNotCancellableException.class)
                .hasMessageContaining("cannot be cancelled in state: SHIPPED");
    }
}
