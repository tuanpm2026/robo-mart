package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.robomart.common.dto.ApiResponse;
import com.robomart.order.client.ProductCatalogClient;
import com.robomart.order.client.ProductSnapshot;
import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.repository.SagaAuditLogRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.saga.SagaRecoveryPolicy;
import com.robomart.order.service.OrderService;
import com.robomart.order.service.PendingPaymentRecoveryService;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import org.testcontainers.containers.PostgreSQLContainer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;

/** Exercises the recovery worker's real transactions, row claims, state transitions and outbox. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "saga.payment-recovery.enabled=false")
@ActiveProfiles("test")
@Import(PendingPaymentRecoveryIT.Database.class)
class PendingPaymentRecoveryIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> recoveryPostgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("payment_recovery_test");
        }
    }

    @MockitoBean(answers = Answers.RETURNS_SELF)
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @MockitoBean(answers = Answers.RETURNS_SELF)
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @Autowired private OrderService orderService;
    @Autowired private OrderSagaOrchestrator orchestrator;
    @Autowired private PendingPaymentRecoveryService recoveryService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private SagaAuditLogRepository sagaAuditLogRepository;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SagaProperties sagaProperties;
    @Autowired private SagaRecoveryPolicy recoveryPolicy;

    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(inventoryStub, paymentStub);
        when(productCatalogClient.getProduct(1L)).thenReturn(
                new ApiResponse<>(new ProductSnapshot(1L, "Recovery Widget", new BigDecimal("25.00")), "trace"));
        resetBreakers();
        lenient().when(paymentStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(paymentStub);
        lenient().when(inventoryStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(inventoryStub);
        when(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class)))
                .thenReturn(ReserveInventoryResponse.newBuilder().setSuccess(true)
                        .setReservationId("recovery-reservation").build());
        lenient().when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());
    }

    @AfterEach
    void postponeUnfinishedTestOrders() {
        for (long id : createdIds) {
            jdbc.update("UPDATE orders SET next_payment_retry_at=CURRENT_TIMESTAMP + INTERVAL '1 day', "
                    + "status=CASE WHEN status='PAYMENT_PROCESSING' THEN 'PAYMENT_PENDING' ELSE status END WHERE id=?", id);
        }
        createdIds.clear();
    }

    @Test
    void resumesPersistedPendingOrderWithoutReservingStockAgain() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        doReturn(success("payment-recovered")).when(paymentStub).processPayment(any(ProcessPaymentRequest.class));

        assertThat(recoveryService.resumeNextPayment()).isTrue();

        Order confirmed = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPaymentId()).isEqualTo("payment-recovered");
        assertThat(confirmed.getReservationId()).isEqualTo("recovery-reservation");
        assertThat(confirmed.getItems()).hasSize(1);
        verify(inventoryStub, times(1)).reserveInventory(any(ReserveInventoryRequest.class));
        assertThat(sagaAuditLogRepository.existsByIdempotencyKeyAndStatus(
                pending.getId() + ":ProcessPayment", "SUCCESS")).isTrue();
        assertThat(outboxEventRepository.findAll())
                .filteredOn(event -> event.getAggregateId().equals(pending.getId().toString())
                        && event.getEventType().equals("order_status_changed") && event.getPayload().contains("CONFIRMED"))
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=? AND status='CONFIRMED'",
                Integer.class, pending.getId())).isEqualTo(1);
        assertThat(recoveryService.resumeNextPayment()).as("Confirmed orders are not retried").isFalse();
    }

    @Test
    void transientRecoveryFailureKeepsReservationAndRetriesTheSameOrderLater() {
        AtomicBoolean available = new AtomicBoolean();
        List<ProcessPaymentRequest> requests = new ArrayList<>();
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            if (!available.get()) {
                throw Status.UNAVAILABLE.asRuntimeException();
            }
            return success("payment-after-retry");
        });
        Order pending = createPendingOrder();

        assertThat(recoveryService.resumeNextPayment()).isTrue();
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(jdbc.queryForObject("SELECT payment_retry_count FROM orders WHERE id=?", Integer.class, pending.getId()))
                .isPositive();
        assertThat(jdbc.queryForObject("SELECT next_payment_retry_at > CURRENT_TIMESTAMP FROM orders WHERE id=?",
                Boolean.class, pending.getId())).isTrue();
        assertThat(recoveryService.resumeNextPayment()).as("Backoff prevents immediate repeated calls").isFalse();

        available.set(true);
        makeDue(pending.getId());
        resetBreakers();
        assertThat(recoveryService.resumeNextPayment()).isTrue();
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.getIdempotencyKey()).isEqualTo(pending.getId().toString());
            assertThat(request.getOrderId()).isEqualTo(pending.getId().toString());
            assertThat(request.getAmount().getAmount()).isEqualTo("25.00");
        });
        verify(inventoryStub, times(1)).reserveInventory(any(ReserveInventoryRequest.class));
    }

    @Test
    void definitiveDeclineCancelsOnlyAfterInventoryIsReleased() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        doThrow(Status.FAILED_PRECONDITION.withDescription("Payment declined").asRuntimeException())
                .when(paymentStub).processPayment(any(ProcessPaymentRequest.class));

        assertThat(recoveryService.resumeNextPayment()).isTrue();

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryStub).releaseInventory(org.mockito.ArgumentMatchers.argThat(request ->
                request.getOrderId().equals(pending.getId().toString()) && request.getItemsCount() == 1));
        assertThat(recoveryService.resumeNextPayment()).as("Cancelled orders are not retried").isFalse();
    }

    @Test
    void failedInventoryCompensationRemainsRetryableUntilReleaseSucceeds() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        doThrow(Status.FAILED_PRECONDITION.withDescription("Payment declined").asRuntimeException())
                .when(paymentStub).processPayment(any(ProcessPaymentRequest.class));
        when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        assertThat(recoveryService.resumeNextPayment()).isTrue();
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        doReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build())
                .when(inventoryStub).releaseInventory(any(ReleaseInventoryRequest.class));
        makeDue(pending.getId());
        resetBreakers();
        assertThat(recoveryService.resumeNextPayment()).isTrue();
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cachedDeclineResponseIsCompensatedRatherThanConfirmed() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        doReturn(ProcessPaymentResponse.newBuilder().setSuccess(false).setMessage("Payment declined").build())
                .when(paymentStub).processPayment(any(ProcessPaymentRequest.class));

        assertThat(recoveryService.resumeNextPayment()).isTrue();

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryStub).releaseInventory(any(ReleaseInventoryRequest.class));
    }

    @Test
    void unsuccessfulReleaseResponseDoesNotFinalizeCancellation() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        doThrow(Status.FAILED_PRECONDITION.withDescription("Payment declined").asRuntimeException())
                .when(paymentStub).processPayment(any(ProcessPaymentRequest.class));
        when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(false).setMessage("Release failed").build());

        assertThat(recoveryService.resumeNextPayment()).isTrue();

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void successfulResponseWithoutPaymentReferenceRemainsPending() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        doReturn(ProcessPaymentResponse.newBuilder().setSuccess(true).build())
                .when(paymentStub).processPayment(any(ProcessPaymentRequest.class));

        assertThat(recoveryService.resumeNextPayment()).isTrue();

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void concurrentWorkersSkipTheOrderAlreadyLockedByAnotherWorker() throws Exception {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent worker test did not release payment call");
            }
            return success("payment-single-worker");
        }).when(paymentStub).processPayment(any(ProcessPaymentRequest.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(recoveryService::resumeNextPayment);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(recoveryService::resumeNextPayment);
                assertThat(second.get(3, TimeUnit.SECONDS)).as("SKIP LOCKED worker does not block or duplicate RPC").isFalse();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(calls).hasValue(1);
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void lostPaymentResponseReplaysTheSameIdempotencyKeyAndOriginalPaymentResult() {
        Map<String, ProcessPaymentResponse> providerResults = new ConcurrentHashMap<>();
        AtomicInteger charges = new AtomicInteger();
        AtomicReference<ProcessPaymentRequest> original = new AtomicReference<>();
        AtomicBoolean dropResponse = new AtomicBoolean(true);
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class))).thenAnswer(invocation -> {
            ProcessPaymentRequest request = invocation.getArgument(0);
            original.compareAndSet(null, request);
            assertThat(request).isEqualTo(original.get());
            ProcessPaymentResponse response = providerResults.computeIfAbsent(request.getIdempotencyKey(), key -> {
                charges.incrementAndGet();
                return success("payment-original-charge");
            });
            if (dropResponse.getAndSet(false)) {
                throw Status.DEADLINE_EXCEEDED.withDescription("Payment settled, response lost").asRuntimeException();
            }
            return response;
        });
        Order pending = createPendingOrder();

        assertThat(recoveryService.resumeNextPayment()).isTrue();

        Order confirmed = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPaymentId()).isEqualTo("payment-original-charge");
        assertThat(charges).hasValue(1);
        assertThat(original.get().getIdempotencyKey()).isEqualTo(pending.getId().toString());
        verify(inventoryStub, times(1)).reserveInventory(any(ReserveInventoryRequest.class));
    }

    @Test
    void staleProcessingPaymentReconcilesOriginalChargeWithoutCompensationOrSecondReservation() {
        Map<String, ProcessPaymentResponse> providerResults = new ConcurrentHashMap<>();
        AtomicInteger charges = new AtomicInteger();
        AtomicBoolean loseResponse = new AtomicBoolean(true);
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class))).thenAnswer(invocation -> {
            ProcessPaymentRequest request = invocation.getArgument(0);
            var result = providerResults.computeIfAbsent(request.getIdempotencyKey(), key -> {
                charges.incrementAndGet();
                return success("payment-before-order-crash");
            });
            if (loseResponse.getAndSet(false)) {
                throw Status.DEADLINE_EXCEEDED.asRuntimeException();
            }
            return result;
        });
        Order pending = createPendingOrder();
        // Model a crash after payment settlement, before the foreground caller persisted its hold state.
        jdbc.update("UPDATE orders SET status='PAYMENT_PROCESSING', "
                + "updated_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE id=?", pending.getId());
        Order uncertain = orderRepository.findById(pending.getId()).orElseThrow();

        orchestrator.handleDeadSaga(uncertain);

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        verify(paymentStub, times(1)).processPayment(any(ProcessPaymentRequest.class));
        verify(inventoryStub, never()).releaseInventory(any(ReleaseInventoryRequest.class));
        assertThat(recoveryService.resumeNextPayment()).isTrue();
        Order confirmed = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPaymentId()).isEqualTo("payment-before-order-crash");
        assertThat(charges).hasValue(1);
        verify(inventoryStub, times(1)).reserveInventory(any(ReserveInventoryRequest.class));
        verify(paymentStub, times(2)).processPayment(org.mockito.ArgumentMatchers.argThat(request ->
                request.getIdempotencyKey().equals(pending.getId().toString())));
    }

    @Test
    void freshProcessingPaymentIsNotRequeuedOrCompensatedByAnotherReplica() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        jdbc.update("UPDATE orders SET status='PAYMENT_PROCESSING', updated_at=CURRENT_TIMESTAMP WHERE id=?", pending.getId());

        orchestrator.handleDeadSaga(orderRepository.findById(pending.getId()).orElseThrow());

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        verify(paymentStub, times(3)).processPayment(any(ProcessPaymentRequest.class));
        verify(inventoryStub, never()).releaseInventory(any(ReleaseInventoryRequest.class));
    }

    @Test
    void staleProcessingSnapshotCannotOverwriteAnOrderAnotherWorkerAlreadyConfirmed() {
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        jdbc.update("UPDATE orders SET status='PAYMENT_PROCESSING', "
                + "updated_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE id=?", pending.getId());
        Order staleSnapshot = orderRepository.findById(pending.getId()).orElseThrow();
        jdbc.update("UPDATE orders SET status='CONFIRMED', updated_at=CURRENT_TIMESTAMP WHERE id=?", pending.getId());

        orchestrator.handleDeadSaga(staleSnapshot);

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(paymentStub, times(3)).processPayment(any(ProcessPaymentRequest.class));
        verify(inventoryStub, never()).releaseInventory(any(ReleaseInventoryRequest.class));
    }

    private Order createPendingOrder() {
        Order order = orderService.createOrder("payment-recovery-test", List.of(
                new OrderService.OrderItemRequest("1", "Recovery Widget", 1, new BigDecimal("25.00"))),
                "1 Recovery Test St", null);
        createdIds.add(order.getId());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        makeDue(order.getId());
        resetBreakers();
        return order;
    }

    private void makeDue(long id) {
        jdbc.update("UPDATE orders SET next_payment_retry_at=NULL WHERE id=?", id);
    }

    private void resetBreakers() {
        circuitBreakerRegistry.circuitBreaker("payment-service").reset();
        circuitBreakerRegistry.circuitBreaker("inventory-service").reset();
    }

    private static ProcessPaymentResponse success(String id) {
        return ProcessPaymentResponse.newBuilder().setSuccess(true).setPaymentId(id)
                .setTransactionId("transaction-" + id).build();
    }    @Test
    void exhaustedPaymentBudgetStopsAutomaticCallsWithoutReleasingStock() {
        when(paymentStub.processPayment(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        reset(paymentStub);
        when(paymentStub.processPayment(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        int previous = sagaProperties.getPaymentRecovery().getMaxAttempts();
        try {
            sagaProperties.getPaymentRecovery().setMaxAttempts(1);
            assertThat(recoveryService.resumeNextPayment()).isTrue();
            assertThat(recoveryPolicy.isManualReview(pending.getId())).isTrue();
            assertThat(recoveryService.resumeNextPayment()).isFalse();
            assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PAYMENT_PENDING);
            verify(paymentStub, times(3)).processPayment(org.mockito.ArgumentMatchers.argThat(request ->
                    request.getIdempotencyKey().equals(pending.getId().toString())));
            verify(inventoryStub, never()).releaseInventory(any());
        } finally {
            sagaProperties.getPaymentRecovery().setMaxAttempts(previous);
        }
    }

    @Test
    void agedUncertainPaymentEscalatesBeforeAnotherRpc() {
        when(paymentStub.processPayment(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        Order pending = createPendingOrder();
        reset(paymentStub);
        jdbc.update("UPDATE orders SET created_at=now() - interval '2 hours' WHERE id=?", pending.getId());

        assertThat(recoveryService.resumeNextPayment()).isTrue();
        assertThat(recoveryPolicy.isManualReview(pending.getId())).isTrue();
        assertThat(recoveryService.resumeNextPayment()).isFalse();
        verify(paymentStub, never()).processPayment(any());
        verify(inventoryStub, never()).releaseInventory(any());
        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getReservationId())
                .isEqualTo("recovery-reservation");
    }

}
