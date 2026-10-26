package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.grpc.InventoryGrpcClient;
import com.robomart.order.grpc.PaymentGrpcClient;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/** Real transport deadlines cancel hanging RPCs, rather than abandoning a background future. */
class GrpcDeadlineIT {

    @Test
    void reserveChargeAndRefundCancelHangingServerCalls() throws Exception {
        CountDownLatch cancellations = new CountDownLatch(3);
        var server = ServerBuilder.forPort(0)
                .addService(new InventoryServiceGrpc.InventoryServiceImplBase() {
                    @Override
                    public void reserveInventory(ReserveInventoryRequest request,
                            StreamObserver<ReserveInventoryResponse> observer) {
                        ((ServerCallStreamObserver<?>) observer).setOnCancelHandler(cancellations::countDown);
                    }
                })
                .addService(new PaymentServiceGrpc.PaymentServiceImplBase() {
                    @Override
                    public void processPayment(ProcessPaymentRequest request,
                            StreamObserver<ProcessPaymentResponse> observer) {
                        ((ServerCallStreamObserver<?>) observer).setOnCancelHandler(cancellations::countDown);
                    }

                    @Override
                    public void refundPayment(RefundPaymentRequest request,
                            StreamObserver<RefundPaymentResponse> observer) {
                        ((ServerCallStreamObserver<?>) observer).setOnCancelHandler(cancellations::countDown);
                    }
                }).build().start();
        var channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
            SagaProperties properties = new SagaProperties();
            properties.getSteps().setDefaultTimeout(Duration.ofSeconds(1));
            var inventory = new InventoryGrpcClient(InventoryServiceGrpc.newBlockingStub(channel), properties);
            var payment = new PaymentGrpcClient(PaymentServiceGrpc.newBlockingStub(channel), properties);
            assertDeadline(() -> inventory.reserveInventory(ReserveInventoryRequest.getDefaultInstance()));
            assertDeadline(() -> payment.processPayment(ProcessPaymentRequest.getDefaultInstance()));
            assertDeadline(() -> payment.refundPayment(RefundPaymentRequest.getDefaultInstance()));
            assertThat(cancellations.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void assertDeadline(Runnable rpc) {
        assertThatThrownBy(rpc::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }
}
