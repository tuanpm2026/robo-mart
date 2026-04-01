package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.entity.SagaAuditLog;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.service.OrderService;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.test.PostgresContainerConfig;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
@DisplayName("OrderSaga Integration Tests")
class OrderSagaIT {

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
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SagaAuditLogRepository sagaAuditLogRepository;

    private List<OrderService.OrderItemRequest> sampleItems;

    @BeforeEach
    void setUp() {
        Mockito.reset(inventoryStub, paymentStub);
        sampleItems = List.of(
                new OrderService.OrderItemRequest("10", "Widget", 2, new BigDecimal("49.99")));
    }

    private void stubInventorySuccess() {
        when(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class)))
                .thenReturn(ReserveInventoryResponse.newBuilder()
                        .setSuccess(true)
                        .setReservationId("res-test-001")
                        .build());
    }

    private void stubPaymentSuccess() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenReturn(ProcessPaymentResponse.newBuilder()
                        .setSuccess(true)
                        .setPaymentId("pay-test-001")
                        .setTransactionId("txn-test-001")
                        .build());
    }

    @Test
    @DisplayName("shouldConfirmOrderAndCreateOutboxEventOnHappyPath")
    void shouldConfirmOrderAndCreateOutboxEventOnHappyPath() {
        stubInventorySuccess();
        stubPaymentSuccess();

        Order order = orderService.createOrder("user-1", sampleItems, "123 Main St, NY, NY, 10001, US");

        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(persisted.getReservationId()).isEqualTo("res-test-001");
        assertThat(persisted.getPaymentId()).isEqualTo("pay-test-001");

        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        assertThat(events).isNotEmpty();
        OutboxEvent event = events.stream()
                .filter(e -> e.getAggregateId().equals(order.getId().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(event.getEventType()).isEqualTo("order_status_changed");
        assertThat(event.getAggregateType()).isEqualTo("Order");
    }

    @Test
    @DisplayName("shouldCancelOrderImmediatelyWhenInventoryFails")
    void shouldCancelOrderImmediatelyWhenInventoryFails() {
        when(inventoryStub.reserveInventory(any()))
                .thenThrow(new StatusRuntimeException(
                        Status.FAILED_PRECONDITION.withDescription("Insufficient stock")));

        Order order = orderService.createOrder("user-2", sampleItems, "456 Oak Ave, LA, CA, 90001, US");

        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(persisted.getCancellationReason()).isEqualTo("Insufficient stock");

        verify(paymentStub, never()).processPayment(any());
    }

    @Test
    @DisplayName("shouldCompensateInventoryAndCancelWhenPaymentFails")
    void shouldCompensateInventoryAndCancelWhenPaymentFails() {
        stubInventorySuccess();
        when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());
        when(paymentStub.processPayment(any()))
                .thenThrow(new StatusRuntimeException(
                        Status.FAILED_PRECONDITION.withDescription("Payment declined")));

        Order order = orderService.createOrder("user-3", sampleItems, "789 Pine Rd, CHI, IL, 60601, US");

        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(persisted.getCancellationReason()).isEqualTo("Payment declined");

        verify(inventoryStub).releaseInventory(any(ReleaseInventoryRequest.class));
    }

    @Test
    @DisplayName("shouldWriteSagaAuditLogEntriesForAllSteps")
    void shouldWriteSagaAuditLogEntriesForAllSteps() {
        stubInventorySuccess();
        stubPaymentSuccess();

        Order order = orderService.createOrder("user-4", sampleItems, "1 Test St, Test, TX, 75001, US");

        List<SagaAuditLog> auditLogs = sagaAuditLogRepository
                .findBySagaIdOrderByExecutedAtAsc(order.getId().toString());
        assertThat(auditLogs).isNotEmpty();

        List<String> stepNames = auditLogs.stream().map(SagaAuditLog::getStepName).toList();
        assertThat(stepNames).contains("ReserveInventory", "ProcessPayment");

        List<String> statuses = auditLogs.stream().map(SagaAuditLog::getStatus).toList();
        assertThat(statuses).contains("STARTED", "SUCCESS");
    }
}
