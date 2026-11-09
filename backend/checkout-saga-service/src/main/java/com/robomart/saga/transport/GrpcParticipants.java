package com.robomart.saga.transport;

import com.robomart.proto.inventory.*;
import com.robomart.proto.payment.*;
import com.robomart.proto.saga.*;
import com.robomart.saga.SagaParticipants;

import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GrpcParticipants implements SagaParticipants {
    private final OrderSagaParticipantServiceGrpc.OrderSagaParticipantServiceBlockingStub orders;
    private final PaymentSagaParticipantServiceGrpc.PaymentSagaParticipantServiceBlockingStub
            outcomes;
    private final PaymentServiceGrpc.PaymentServiceBlockingStub payments;
    private final InventoryServiceGrpc.InventoryServiceBlockingStub inventory;
    private final ServiceTokenProvider tokens;
    private final ParticipantCircuitBreakers breakers;

    public GrpcParticipants(
            GrpcChannelFactory channels,
            ServiceTokenProvider tokens,
            ParticipantCircuitBreakers breakers) {
        this.tokens = tokens;
        this.breakers = breakers;
        Channel order = channels.createChannel("order-service"),
                payment = channels.createChannel("payment-service");
        orders = OrderSagaParticipantServiceGrpc.newBlockingStub(order);
        outcomes = PaymentSagaParticipantServiceGrpc.newBlockingStub(payment);
        payments = PaymentServiceGrpc.newBlockingStub(payment);
        inventory =
                InventoryServiceGrpc.newBlockingStub(channels.createChannel("inventory-service"));
    }

    private io.grpc.ClientInterceptor auth() {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + tokens.get());
        headers.put(
                Metadata.Key.of("x-saga-instance", Metadata.ASCII_STRING_MARSHALLER),
                System.getenv().getOrDefault("HOSTNAME", "local"));
        return MetadataUtils.newAttachHeadersInterceptor(headers);
    }

    public WorkflowOrderResponse order(WorkflowOrderRequest q) {
        return breakers.call(
                "order-service",
                () ->
                        orders.withInterceptors(auth())
                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                                .getWorkflowOrder(
                                        GetWorkflowOrderRequest.newBuilder()
                                                .setOperation(q)
                                                .build())
                                .getResult());
    }

    public OrderWorkflowResult command(OrderWorkflowCommand q) {
        return breakers.call(
                "order-service",
                () ->
                        orders.withInterceptors(auth())
                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                                .executeCommand(
                                        ExecuteCommandRequest.newBuilder().setCommand(q).build())
                                .getResult());
    }

    public ReserveInventoryResponse reserve(ReserveInventoryRequest q) {
        return breakers.call(
                "inventory-service",
                () ->
                        inventory
                                .withInterceptors(auth())
                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                                .reserveInventory(q));
    }

    public ReleaseInventoryResponse release(ReleaseInventoryRequest q) {
        return breakers.call(
                "inventory-service",
                () ->
                        inventory
                                .withInterceptors(auth())
                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                                .releaseInventory(q));
    }

    public ChargeOutcome charge(ChargeOperation q, boolean close) {
        return breakers.call(
                "payment-service",
                () -> {
                    var stub =
                            outcomes.withInterceptors(auth())
                                    .withDeadlineAfter(5, TimeUnit.SECONDS);
                    return close
                            ? stub.closeCharge(
                                            CloseChargeRequest.newBuilder().setOperation(q).build())
                                    .getResult()
                            : stub.getChargeOutcome(
                                            GetChargeOutcomeRequest.newBuilder()
                                                    .setOperation(q)
                                                    .build())
                                    .getResult();
                });
    }

    public ProcessPaymentResponse pay(ProcessPaymentRequest q) {
        return breakers.call(
                "payment-service",
                () ->
                        payments.withInterceptors(auth())
                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                                .processPayment(q));
    }

    public RefundOutcome refund(RefundOperation q, boolean execute) {
        return breakers.call(
                "payment-service",
                () -> {
                    var stub =
                            outcomes.withInterceptors(auth())
                                    .withDeadlineAfter(5, TimeUnit.SECONDS);
                    return execute
                            ? stub.refundRemaining(
                                            RefundRemainingRequest.newBuilder()
                                                    .setOperation(q)
                                                    .build())
                                    .getResult()
                            : stub.getRefundOutcome(
                                            GetRefundOutcomeRequest.newBuilder()
                                                    .setOperation(q)
                                                    .build())
                                    .getResult();
                });
    }
}
