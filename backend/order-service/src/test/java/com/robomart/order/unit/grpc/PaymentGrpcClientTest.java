package com.robomart.order.unit.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.order.grpc.PaymentGrpcClient;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentGrpcClient")
class PaymentGrpcClientTest {

    @Mock
    private PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    private PaymentGrpcClient client;

    @BeforeEach
    void setUp() {
        client = new PaymentGrpcClient(stub);
    }

    @Test
    @DisplayName("shouldReturnResponseWhenProcessPaymentSucceeds")
    void shouldReturnResponseWhenProcessPaymentSucceeds() {
        when(stub.processPayment(any(ProcessPaymentRequest.class)))
                .thenReturn(ProcessPaymentResponse.newBuilder()
                        .setSuccess(true)
                        .setPaymentId("pay-001")
                        .setTransactionId("txn-abc")
                        .build());

        ProcessPaymentResponse response = client.processPayment(
                ProcessPaymentRequest.newBuilder().setOrderId("order-1").build());

        assertThat(response.getPaymentId()).isEqualTo("pay-001");
        assertThat(response.getSuccess()).isTrue();
    }

    @Test
    @DisplayName("shouldReturnResponseWhenRefundPaymentSucceeds")
    void shouldReturnResponseWhenRefundPaymentSucceeds() {
        when(stub.refundPayment(any(RefundPaymentRequest.class)))
                .thenReturn(RefundPaymentResponse.newBuilder()
                        .setSuccess(true)
                        .setRefundTransactionId("refund-txn-789")
                        .build());

        RefundPaymentResponse response = client.refundPayment(
                RefundPaymentRequest.newBuilder().setPaymentId("pay-001").build());

        assertThat(response.getRefundTransactionId()).isEqualTo("refund-txn-789");
        assertThat(response.getSuccess()).isTrue();
    }
}
