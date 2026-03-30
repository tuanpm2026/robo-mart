package com.robomart.payment.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@GrpcService
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    @Override
    public void processPayment(ProcessPaymentRequest request, StreamObserver<ProcessPaymentResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("ProcessPayment not yet implemented").asRuntimeException());
    }

    @Override
    public void refundPayment(RefundPaymentRequest request, StreamObserver<RefundPaymentResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("RefundPayment not yet implemented").asRuntimeException());
    }
}
