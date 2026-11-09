package com.robomart.order.grpc;

import com.robomart.order.workflow.OrderWorkflowCoordinator;
import com.robomart.proto.saga.*;
import com.robomart.security.grpc.SagaRpcAuthInterceptor;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import org.springframework.grpc.server.service.GrpcService;

import java.util.function.Supplier;

@GrpcService(interceptors = SagaRpcAuthInterceptor.class)
public class OrderSagaGrpcService
        extends OrderSagaParticipantServiceGrpc.OrderSagaParticipantServiceImplBase {
    private final OrderWorkflowCoordinator service;

    public OrderSagaGrpcService(OrderWorkflowCoordinator service) {
        this.service = service;
    }

    @Override
    public void getWorkflowOrder(
            GetWorkflowOrderRequest q, StreamObserver<GetWorkflowOrderResponse> out) {
        reply(
                out,
                () ->
                        GetWorkflowOrderResponse.newBuilder()
                                .setResult(service.get(q.getOperation()))
                                .build());
    }

    @Override
    public void executeCommand(
            ExecuteCommandRequest q, StreamObserver<ExecuteCommandResponse> out) {
        reply(
                out,
                () ->
                        ExecuteCommandResponse.newBuilder()
                                .setResult(service.execute(q.getCommand()))
                                .build());
    }

    private static <T> void reply(StreamObserver<T> out, Supplier<T> action) {
        try {
            out.onNext(action.get());
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            out.onError(e);
        } catch (IllegalArgumentException e) {
            out.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            out.onError(
                    Status.UNAVAILABLE
                            .withDescription("Order command unavailable")
                            .asRuntimeException());
        }
    }
}
