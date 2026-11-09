package com.robomart.saga;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.robomart.proto.inventory.*;
import com.robomart.proto.payment.*;
import com.robomart.proto.saga.*;
import com.robomart.saga.transport.*;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.grpc.*;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.Test;
import org.springframework.grpc.client.GrpcChannelFactory;

import java.util.concurrent.atomic.AtomicInteger;

class GrpcParticipantsTest {
    @Test
    void allCallsCarryServiceIdentityDeadlineAndOriginalPayload() throws Exception {
        var failedPaymentCalls = new AtomicInteger();
        var auth =
                new ServerInterceptor() {
                    public <Q, R> ServerCall.Listener<Q> interceptCall(
                            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                        assertThat(
                                        headers.get(
                                                Metadata.Key.of(
                                                        "authorization",
                                                        Metadata.ASCII_STRING_MARSHALLER)))
                                .isEqualTo("Bearer signed-token");
                        assertThat(Context.current().getDeadline()).isNotNull();
                        return next.startCall(call, headers);
                    }
                };
        Server server =
                ServerBuilder.forPort(0)
                        .intercept(auth)
                        .addService(
                                new OrderSagaParticipantServiceGrpc
                                        .OrderSagaParticipantServiceImplBase() {
                                    public void getWorkflowOrder(
                                            GetWorkflowOrderRequest q,
                                            StreamObserver<GetWorkflowOrderResponse> o) {
                                        reply(
                                                o,
                                                GetWorkflowOrderResponse.newBuilder()
                                                        .setResult(
                                                                WorkflowOrderResponse.newBuilder()
                                                                        .setStatus(
                                                                                q.getOperation()
                                                                                        .getOrderId())
                                                                        .build())
                                                        .build());
                                    }

                                    public void executeCommand(
                                            ExecuteCommandRequest q,
                                            StreamObserver<ExecuteCommandResponse> o) {
                                        reply(
                                                o,
                                                ExecuteCommandResponse.newBuilder()
                                                        .setResult(
                                                                OrderWorkflowResult.newBuilder()
                                                                        .setStatus(
                                                                                q.getCommand()
                                                                                        .getCommandId())
                                                                        .build())
                                                        .build());
                                    }
                                })
                        .addService(
                                new PaymentSagaParticipantServiceGrpc
                                        .PaymentSagaParticipantServiceImplBase() {
                                    public void getChargeOutcome(
                                            GetChargeOutcomeRequest q,
                                            StreamObserver<GetChargeOutcomeResponse> o) {
                                        if ("offline".equals(q.getOperation().getOrderId())) {
                                            failedPaymentCalls.incrementAndGet();
                                            o.onError(Status.UNAVAILABLE.asRuntimeException());
                                            return;
                                        }
                                        reply(
                                                o,
                                                GetChargeOutcomeResponse.newBuilder()
                                                        .setResult(
                                                                ChargeOutcome.newBuilder()
                                                                        .setState(
                                                                                q.getOperation()
                                                                                        .getIdempotencyKey())
                                                                        .build())
                                                        .build());
                                    }

                                    public void closeCharge(
                                            CloseChargeRequest q,
                                            StreamObserver<CloseChargeResponse> o) {
                                        reply(
                                                o,
                                                CloseChargeResponse.newBuilder()
                                                        .setResult(
                                                                ChargeOutcome.newBuilder()
                                                                        .setState("closed")
                                                                        .build())
                                                        .build());
                                    }

                                    public void getRefundOutcome(
                                            GetRefundOutcomeRequest q,
                                            StreamObserver<GetRefundOutcomeResponse> o) {
                                        reply(
                                                o,
                                                GetRefundOutcomeResponse.newBuilder()
                                                        .setResult(
                                                                RefundOutcome.newBuilder()
                                                                        .setState(
                                                                                q.getOperation()
                                                                                        .getIdempotencyKey())
                                                                        .build())
                                                        .build());
                                    }

                                    public void refundRemaining(
                                            RefundRemainingRequest q,
                                            StreamObserver<RefundRemainingResponse> o) {
                                        reply(
                                                o,
                                                RefundRemainingResponse.newBuilder()
                                                        .setResult(
                                                                RefundOutcome.newBuilder()
                                                                        .setState("refunded")
                                                                        .build())
                                                        .build());
                                    }
                                })
                        .addService(
                                new PaymentServiceGrpc.PaymentServiceImplBase() {
                                    public void processPayment(
                                            ProcessPaymentRequest q,
                                            StreamObserver<ProcessPaymentResponse> o) {
                                        reply(
                                                o,
                                                ProcessPaymentResponse.newBuilder()
                                                        .setPaymentId(q.getIdempotencyKey())
                                                        .build());
                                    }
                                })
                        .addService(
                                new InventoryServiceGrpc.InventoryServiceImplBase() {
                                    public void reserveInventory(
                                            ReserveInventoryRequest q,
                                            StreamObserver<ReserveInventoryResponse> o) {
                                        reply(
                                                o,
                                                ReserveInventoryResponse.newBuilder()
                                                        .setReservationId(q.getOrderId())
                                                        .build());
                                    }

                                    public void releaseInventory(
                                            ReleaseInventoryRequest q,
                                            StreamObserver<ReleaseInventoryResponse> o) {
                                        reply(
                                                o,
                                                ReleaseInventoryResponse.newBuilder()
                                                        .setSuccess(true)
                                                        .build());
                                    }
                                })
                        .build()
                        .start();
        ManagedChannel channel =
                ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort())
                        .usePlaintext()
                        .build();
        try {
            GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
            when(factory.createChannel(anyString())).thenReturn(channel);
            ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
            when(tokens.get()).thenReturn("signed-token");
            var port = new GrpcParticipants(factory, tokens, new ParticipantCircuitBreakers());
            assertThat(
                            port.order(WorkflowOrderRequest.newBuilder().setOrderId("42").build())
                                    .getStatus())
                    .isEqualTo("42");
            assertThat(
                            port.command(
                                            OrderWorkflowCommand.newBuilder()
                                                    .setCommandId("stable")
                                                    .build())
                                    .getStatus())
                    .isEqualTo("stable");
            assertThat(
                            port.reserve(
                                            ReserveInventoryRequest.newBuilder()
                                                    .setOrderId("42")
                                                    .build())
                                    .getReservationId())
                    .isEqualTo("42");
            assertThat(port.release(ReleaseInventoryRequest.getDefaultInstance()).getSuccess())
                    .isTrue();
            var q = ChargeOperation.newBuilder().setIdempotencyKey("42").build();
            assertThat(port.charge(q, false).getState()).isEqualTo("42");
            assertThat(port.charge(q, true).getState()).isEqualTo("closed");
            assertThat(
                            port.pay(
                                            ProcessPaymentRequest.newBuilder()
                                                    .setIdempotencyKey("42")
                                                    .build())
                                    .getPaymentId())
                    .isEqualTo("42");
            var refund = RefundOperation.newBuilder().setIdempotencyKey("saga:refund").build();
            assertThat(port.refund(refund, false).getState()).isEqualTo("saga:refund");
            assertThat(port.refund(refund, true).getState()).isEqualTo("refunded");
            var unavailable = ChargeOperation.newBuilder().setOrderId("offline").build();
            for (int i = 0; i < 5; i++)
                assertThatThrownBy(() -> port.charge(unavailable, false))
                        .isInstanceOf(StatusRuntimeException.class);
            assertThatThrownBy(() -> port.charge(q, false))
                    .isInstanceOf(CallNotPermittedException.class);
            assertThat(failedPaymentCalls).hasValue(5);
            assertThat(port.release(ReleaseInventoryRequest.getDefaultInstance()).getSuccess())
                    .isTrue();
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    private static <T> void reply(StreamObserver<T> o, T value) {
        o.onNext(value);
        o.onCompleted();
    }
}
