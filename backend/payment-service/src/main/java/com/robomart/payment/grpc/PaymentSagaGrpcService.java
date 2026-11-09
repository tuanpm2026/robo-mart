package com.robomart.payment.grpc;

import com.robomart.payment.service.PaymentWorkflowService;
import com.robomart.proto.saga.*;
import com.robomart.security.grpc.SagaRpcAuthInterceptor;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import org.springframework.grpc.server.service.GrpcService;

import java.util.function.Supplier;

@GrpcService(interceptors = SagaRpcAuthInterceptor.class)
public class PaymentSagaGrpcService
        extends PaymentSagaParticipantServiceGrpc.PaymentSagaParticipantServiceImplBase {
    private final PaymentWorkflowService service;

    public PaymentSagaGrpcService(PaymentWorkflowService service) {
        this.service = service;
    }

    @Override
    public void getChargeOutcome(
            GetChargeOutcomeRequest q, StreamObserver<GetChargeOutcomeResponse> out) {
        reply(
                out,
                () ->
                        GetChargeOutcomeResponse.newBuilder()
                                .setResult(service.charge(q.getOperation(), false))
                                .build());
    }

    @Override
    public void closeCharge(CloseChargeRequest q, StreamObserver<CloseChargeResponse> out) {
        reply(
                out,
                () ->
                        CloseChargeResponse.newBuilder()
                                .setResult(service.charge(q.getOperation(), true))
                                .build());
    }

    @Override
    public void refundRemaining(
            RefundRemainingRequest q, StreamObserver<RefundRemainingResponse> out) {
        reply(
                out,
                () ->
                        RefundRemainingResponse.newBuilder()
                                .setResult(service.refund(q.getOperation(), true))
                                .build());
    }

    @Override
    public void getRefundOutcome(
            GetRefundOutcomeRequest q, StreamObserver<GetRefundOutcomeResponse> out) {
        reply(
                out,
                () ->
                        GetRefundOutcomeResponse.newBuilder()
                                .setResult(service.refund(q.getOperation(), false))
                                .build());
    }

    private static <T> void reply(StreamObserver<T> out, Supplier<T> operation) {
        try {
            out.onNext(operation.get());
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            out.onError(e);
        } catch (IllegalArgumentException e) {
            out.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            out.onError(
                    Status.UNAVAILABLE
                            .withDescription("Payment outcome unavailable")
                            .asRuntimeException());
        }
    }
}
