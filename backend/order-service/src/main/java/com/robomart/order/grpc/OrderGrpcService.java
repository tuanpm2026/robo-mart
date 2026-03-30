package com.robomart.order.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.robomart.proto.order.CancelOrderRequest;
import com.robomart.proto.order.CancelOrderResponse;
import com.robomart.proto.order.CreateOrderRequest;
import com.robomart.proto.order.CreateOrderResponse;
import com.robomart.proto.order.GetOrderRequest;
import com.robomart.proto.order.GetOrderResponse;
import com.robomart.proto.order.OrderServiceGrpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@GrpcService
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    @Override
    public void createOrder(CreateOrderRequest request, StreamObserver<CreateOrderResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("CreateOrder not yet implemented").asRuntimeException());
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("GetOrder not yet implemented").asRuntimeException());
    }

    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("CancelOrder not yet implemented").asRuntimeException());
    }
}
