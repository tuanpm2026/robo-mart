package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.robomart.common.dto.ApiResponse;
import com.robomart.order.client.ProductCatalogClient;
import com.robomart.order.client.ProductSnapshot;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.service.OrderService;
import com.robomart.order.workflow.OrderWorkflowCoordinator;
import com.robomart.proto.saga.*;
import com.robomart.test.PostgresContainerConfig;
import com.robomart.test.security.TestJwtDecoderConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties = {
            "spring.grpc.server.port=0",
            "robomart.order.external-saga.enabled=true",
            "saga.dead-saga-detection.enabled=false",
            "outbox.initial-delay-ms=3600000"
        })
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, TestJwtDecoderConfig.class})
class ExternalOrderWorkflowIT {
    @Autowired OrderService orders;
    @Autowired org.springframework.grpc.server.lifecycle.GrpcServerLifecycle grpcServer;
    @Autowired OrderWorkflowCoordinator workflow;
    @Autowired OrderRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @MockitoBean ProductCatalogClient catalog;
    @MockitoBean KafkaTemplate<Object, Object> kafka;

    @BeforeEach
    void pricing() {
        when(catalog.getProduct(10L))
                .thenReturn(
                        new ApiResponse<>(
                                new ProductSnapshot(10L, "Priced", new BigDecimal("20.00")),
                                "trace"));
    }

    Order create(String key) {
        return orders.createOrder(
                "user",
                List.of(
                        new OrderService.OrderItemRequest(
                                "10", "untrusted", 1, new BigDecimal("0.01"))),
                "address",
                key);
    }

    Order create() {
        return create(UUID.randomUUID().toString());
    }

    OrderWorkflowCommand.Builder command(Order o, String action) {
        return OrderWorkflowCommand.newBuilder()
                .setOrderId(o.getId().toString())
                .setSagaId(o.getSagaId().toString())
                .setOwnerEpoch(o.getOrchestratorEpoch())
                .setAction(action)
                .setCommandId(o.getSagaId() + ":" + action);
    }

    @Test
    void creationAtomicallyStoresPricedSnapshotAndStartOutbox() {
        var o = create();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(o.getOrchestratorOwner()).isEqualTo("EXTERNAL");
        assertThat(o.getCheckoutSnapshot()).contains("20.00").doesNotContain("untrusted");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND"
                                        + " event_type='checkout_saga_requested'",
                                Integer.class,
                                o.getId().toString()))
                .isEqualTo(1);
        String key = UUID.randomUUID().toString();
        tx.executeWithoutResult(
                s -> {
                    create(key);
                    s.setRollbackOnly();
                });
        assertThat(repository.findByUserIdAndIdempotencyKey("user", key)).isEmpty();
    }

    @Test
    void replayUsesOriginalSnapshotAndRejectsChangedRequest() {
        String key = UUID.randomUUID().toString();
        Order first = create(key);
        assertThat(create(key).getId()).isEqualTo(first.getId());
        assertThatThrownBy(
                        () ->
                                orders.createOrder(
                                        "user",
                                        List.of(
                                                new OrderService.OrderItemRequest(
                                                        "10", "ignored", 2, BigDecimal.ONE)),
                                        "address",
                                        key))
                .hasMessageContaining("different checkout");
    }

    @Test
    void commandReceiptSurvivesLaterShippingAndPayloadConflictIsRejected() {
        var o = create();
        var q = command(o, "CONFIRM").setPaymentId("9").setReservationId("res").build();
        var receipt = workflow.execute(q);
        assertThat(receipt.getApplied()).isTrue();
        orders.updateOrderStatus(o.getId(), OrderStatus.SHIPPED);
        assertThat(workflow.execute(q)).isEqualTo(receipt);
        assertThat(repository.findById(o.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
        assertThatThrownBy(() -> workflow.execute(q.toBuilder().setPaymentId("different").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM order_status_history WHERE order_id=? AND"
                                        + " status='CONFIRMED'",
                                Integer.class,
                                o.getId()))
                .isEqualTo(1);
    }

    @Test
    void customerCancellationWinsConfirmationAndCannotShip() {
        var o = create();
        orders.cancelOrder(o.getId(), "Changed my mind", "user");
        assertThat(
                        workflow.execute(
                                        command(o, "CONFIRM")
                                                .setPaymentId("9")
                                                .setReservationId("res")
                                                .build())
                                .getApplied())
                .isFalse();
        assertThatThrownBy(() -> orders.updateOrderStatus(o.getId(), OrderStatus.SHIPPED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(o.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLATION_PENDING);
    }

    @Test
    void finalCancellationRequiresTerminalMoneyAndInventoryProof() {
        var o = create();
        orders.cancelOrder(o.getId(), "reason", "user");
        var invalid =
                command(o, "FINALIZE_CANCEL")
                        .setChargeResolution("UNKNOWN")
                        .setInventoryClosed(true)
                        .build();
        assertThat(workflow.execute(invalid).getApplied()).isFalse();
        var valid =
                invalid.toBuilder()
                        .setCommandId(o.getSagaId() + ":final-valid")
                        .setChargeResolution("NO_CHARGE_FINAL")
                        .build();
        assertThat(workflow.execute(valid).getApplied()).isTrue();
        assertThat(workflow.execute(valid).getApplied()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND"
                                        + " event_type='order_cancelled'",
                                Integer.class,
                                o.getId().toString()))
                .isEqualTo(1);
    }

    @Test
    void publicRpcReportsPendingStatesWithoutPromisingCompletion() {
        var channel =
                io.grpc.ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort())
                        .usePlaintext()
                        .build();
        try {
            var rpc =
                    com.robomart.proto.order.OrderServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS);
            var created =
                    rpc.createOrder(
                            com.robomart.proto.order.CreateOrderRequest.newBuilder()
                                    .setUserId("user")
                                    .setIdempotencyKey(UUID.randomUUID().toString())
                                    .addItems(
                                            com.robomart.proto.order.OrderItemRequest.newBuilder()
                                                    .setProductId("10")
                                                    .setQuantity(1))
                                    .build());
            assertThat(created.getStatus()).isEqualTo("PENDING");
            var cancelled =
                    rpc.cancelOrder(
                            com.robomart.proto.order.CancelOrderRequest.newBuilder()
                                    .setOrderId(created.getOrderId())
                                    .setCancelledBy("user")
                                    .setReason("cancel")
                                    .build());
            assertThat(cancelled.getStatus()).isEqualTo("CANCELLATION_PENDING");
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void participantRpcRequiresSagaIdentityAndDeduplicatesConcurrentCommands() throws Exception {
        var o = create();
        var channel =
                io.grpc.ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort())
                        .usePlaintext()
                        .build();
        try {
            var stub =
                    OrderSagaParticipantServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS);
            var get =
                    WorkflowOrderRequest.newBuilder()
                            .setOrderId(o.getId().toString())
                            .setSagaId(o.getSagaId().toString())
                            .setOwnerEpoch(1)
                            .build();
            assertThatThrownBy(
                            () ->
                                    stub.getWorkflowOrder(
                                            GetWorkflowOrderRequest.newBuilder()
                                                    .setOperation(get)
                                                    .build()))
                    .hasMessageContaining("UNAUTHENTICATED");
            var customer =
                    stub.withInterceptors(token(com.robomart.test.security.TestJwt.CUSTOMER_TOKEN));
            assertThatThrownBy(
                            () ->
                                    customer.getWorkflowOrder(
                                            GetWorkflowOrderRequest.newBuilder()
                                                    .setOperation(get)
                                                    .build()))
                    .hasMessageContaining("PERMISSION_DENIED");
            var internal =
                    stub.withInterceptors(token(com.robomart.test.security.TestJwt.SAGA_TOKEN));
            assertThat(
                            internal.getWorkflowOrder(
                                            GetWorkflowOrderRequest.newBuilder()
                                                    .setOperation(get)
                                                    .build())
                                    .getResult()
                                    .getStatus())
                    .isEqualTo("PENDING");
            var q = command(o, "CONFIRM").setReservationId("res").setPaymentId("9").build();
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var a =
                        pool.submit(
                                () ->
                                        internal.executeCommand(
                                                ExecuteCommandRequest.newBuilder()
                                                        .setCommand(q)
                                                        .build()));
                var b =
                        pool.submit(
                                () ->
                                        internal.executeCommand(
                                                ExecuteCommandRequest.newBuilder()
                                                        .setCommand(q)
                                                        .build()));
                assertThat(a.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isEqualTo(b.get(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM order_status_history WHERE order_id=? AND"
                                            + " status='CONFIRMED'",
                                    Integer.class,
                                    o.getId()))
                    .isEqualTo(1);
        } finally {
            channel.shutdownNow();
        }
    }

    private io.grpc.ClientInterceptor token(String token) {
        var headers = new io.grpc.Metadata();
        headers.put(
                io.grpc.Metadata.Key.of("authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers);
    }

    @Test
    void shippingAndCancellationHaveOneSerializedWinner() throws Exception {
        for (int i = 0; i < 4; i++) {
            var o = create();
            workflow.execute(
                    command(o, "CONFIRM").setReservationId("res").setPaymentId("9").build());
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var ship =
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        orders.updateOrderStatus(o.getId(), OrderStatus.SHIPPED);
                                        return true;
                                    } catch (RuntimeException rejected) {
                                        return false;
                                    }
                                });
                var cancel =
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        orders.cancelOrder(o.getId(), "race", "user");
                                        return true;
                                    } catch (RuntimeException rejected) {
                                        return false;
                                    }
                                });
                start.countDown();
                boolean shipped = ship.get(5, java.util.concurrent.TimeUnit.SECONDS),
                        cancelled = cancel.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(shipped ^ cancelled).isTrue();
                assertThat(repository.findById(o.getId()).orElseThrow().getStatus())
                        .isEqualTo(
                                shipped ? OrderStatus.SHIPPED : OrderStatus.CANCELLATION_PENDING);
            }
        }
    }

    @Test
    void explicitHandoverPreservesOriginalReferencesAndFencesOldWorker() {
        var o = create();
        workflow.execute(
                command(o, "CONFIRM").setReservationId("original-res").setPaymentId("9").build());
        // A historical confirmed row predates external ownership. Only the explicit handover may
        // change it.
        jdbc.update(
                "UPDATE orders SET"
                    + " orchestrator_owner='EMBEDDED',orchestrator_epoch=0,saga_id=NULL,checkout_snapshot=NULL"
                    + " WHERE id=?",
                o.getId());
        String adopted =
                workflow.adoptConfirmed(
                        o.getId(), "operator", "Confirmed legacy lifecycle handover");
        var current = repository.findById(o.getId()).orElseThrow();
        assertThat(current.getSagaId().toString()).isEqualTo(adopted);
        assertThat(current.getPaymentId()).isEqualTo("9");
        assertThat(current.getReservationId()).isEqualTo("original-res");
        assertThatThrownBy(() -> workflow.adoptConfirmed(o.getId(), "operator", "Repeat"))
                .isInstanceOf(IllegalArgumentException.class);
        boolean changed =
                Boolean.TRUE.equals(
                        tx.execute(
                                s ->
                                        repository.transitionStatus(
                                                current,
                                                OrderStatus.CONFIRMED,
                                                OrderStatus.PAYMENT_REFUNDING)));
        assertThat(changed).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM order_workflow_handover_audit WHERE"
                                        + " order_id=?",
                                Integer.class,
                                o.getId()))
                .isEqualTo(1);
    }

    @Test
    void paidOrderCannotUseNoChargeOrWrongPaymentAsFinalCancellationProof() {
        var o = create();
        workflow.execute(command(o, "CONFIRM").setReservationId("res").setPaymentId("9").build());
        orders.cancelOrder(o.getId(), "cancel", "user");
        assertThat(
                        workflow.execute(
                                        command(o, "FINALIZE_CANCEL")
                                                .setCommandId(o.getSagaId() + ":no-charge")
                                                .setInventoryClosed(true)
                                                .setChargeResolution("NO_CHARGE_FINAL")
                                                .build())
                                .getApplied())
                .isFalse();
        assertThat(
                        workflow.execute(
                                        command(o, "FINALIZE_CANCEL")
                                                .setCommandId(o.getSagaId() + ":wrong-payment")
                                                .setInventoryClosed(true)
                                                .setChargeResolution("REFUNDED")
                                                .setPaymentId("10")
                                                .build())
                                .getApplied())
                .isFalse();
        assertThat(
                        workflow.execute(
                                        command(o, "FINALIZE_CANCEL")
                                                .setCommandId(o.getSagaId() + ":no-release")
                                                .setInventoryClosed(false)
                                                .setChargeResolution("REFUNDED")
                                                .setPaymentId("9")
                                                .build())
                                .getApplied())
                .isFalse();
        assertThat(
                        workflow.execute(
                                        command(o, "FINALIZE_CANCEL")
                                                .setCommandId(o.getSagaId() + ":good-proof")
                                                .setInventoryClosed(true)
                                                .setChargeResolution("REFUNDED")
                                                .setPaymentId("9")
                                                .build())
                                .getApplied())
                .isTrue();
    }

    @Test
    void externalOwnershipFencesEmbeddedWritesAndWrongEpoch() {
        var o = create();
        boolean changed =
                Boolean.TRUE.equals(
                        tx.execute(
                                s ->
                                        repository.transitionStatus(
                                                o,
                                                OrderStatus.PENDING,
                                                OrderStatus.INVENTORY_RESERVING)));
        assertThat(changed).isFalse();
        assertThatThrownBy(() -> workflow.execute(command(o, "CONFIRM").setOwnerEpoch(2).build()))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .hasMessageContaining("PERMISSION_DENIED");
    }
}
