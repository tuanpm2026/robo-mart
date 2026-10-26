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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.robomart.common.dto.ApiResponse;
import com.robomart.order.client.ProductCatalogClient;
import com.robomart.order.client.ProductSnapshot;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.entity.SagaAuditLog;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderItemRepository;
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
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;
import com.robomart.test.PostgresContainerConfig;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

import tools.jackson.databind.json.JsonMapper;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, TestJwtDecoderConfig.class})
@DisplayName("OrderSaga Integration Tests")
class OrderSagaIT {

    @MockitoBean
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @MockitoBean
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @LocalServerPort
    private int port;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderItemRepository orderItemRepository;

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
        when(productCatalogClient.getProduct(10L)).thenReturn(
                new ApiResponse<>(new ProductSnapshot(10L, "Widget", new BigDecimal("49.99")), "trace"));
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

        Order order = orderService.createOrder("user-1", sampleItems, "123 Main St, NY, NY, 10001, US", null);

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
    void shouldCreateAndCancelThroughHttpWithoutReplacingManagedItems() {
        stubInventorySuccess();
        stubPaymentSuccess();
        when(paymentStub.refundPayment(any(RefundPaymentRequest.class)))
                .thenReturn(RefundPaymentResponse.newBuilder().setSuccess(true).build());
        when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", TestJwt.customerBearer())
                .defaultHeader("X-User-Id", "user-http-orphan-regression")
                .defaultStatusHandler(status -> status.isError(), (request, response) -> { })
                .build();

        // A real web request keeps the EntityManager open across the saga's short transactions.
        // Replacing Order.items here used to fail at flush even though direct service ITs passed.
        var created = client.post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"items":[{"productId":"10","productName":"HTTP Widget",
                        "quantity":2,"unitPrice":49.99}],"shippingAddress":"123 HTTP Test St"}
                        """)
                .retrieve().toEntity(String.class);
        assertThat(created.getStatusCode()).as("Checkout response: %s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        var data = JsonMapper.builder().build().readTree(created.getBody()).path("data");
        long id = data.path("id").asLong();
        assertThat(data.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderItemRepository.findByOrderId(id)).singleElement().satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getSubtotal()).isEqualByComparingTo("99.98");
        });

        var cancelled = client.post().uri("/api/v1/orders/" + id + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).body("{\"reason\":\"HTTP regression cancellation\"}")
                .retrieve().toEntity(String.class);
        assertThat(cancelled.getStatusCode()).as("Cancellation response: %s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderItemRepository.findByOrderId(id)).hasSize(1);
        verify(paymentStub).refundPayment(any(RefundPaymentRequest.class));
        verify(inventoryStub).releaseInventory(any(ReleaseInventoryRequest.class));
    }

    @Test
    @DisplayName("shouldCancelOrderImmediatelyWhenInventoryFails")
    void shouldCancelOrderImmediatelyWhenInventoryFails() {
        when(inventoryStub.reserveInventory(any()))
                .thenThrow(new StatusRuntimeException(
                        Status.FAILED_PRECONDITION.withDescription("Insufficient stock")));

        Order order = orderService.createOrder("user-2", sampleItems, "456 Oak Ave, LA, CA, 90001, US", null);

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

        Order order = orderService.createOrder("user-3", sampleItems, "789 Pine Rd, CHI, IL, 60601, US", null);

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

        Order order = orderService.createOrder("user-4", sampleItems, "1 Test St, Test, TX, 75001, US", null);

        List<SagaAuditLog> auditLogs = sagaAuditLogRepository
                .findBySagaIdOrderByExecutedAtAsc(order.getId().toString());
        assertThat(auditLogs).isNotEmpty();

        List<String> stepNames = auditLogs.stream().map(SagaAuditLog::getStepName).toList();
        assertThat(stepNames).contains("ReserveInventory", "ProcessPayment");

        List<String> statuses = auditLogs.stream().map(SagaAuditLog::getStatus).toList();
        assertThat(statuses).contains("STARTED", "SUCCESS");
    }
}
