package com.robomart.payment.unit.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.grpc.PaymentGrpcService;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.service.PaymentService.PaymentResult;
import com.robomart.proto.common.Money;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentGrpcService Unit Tests")
class PaymentGrpcServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private StreamObserver<ProcessPaymentResponse> processResponseObserver;

    @Mock
    private StreamObserver<RefundPaymentResponse> refundResponseObserver;

    @Captor
    private ArgumentCaptor<ProcessPaymentResponse> processResponseCaptor;

    @Captor
    private ArgumentCaptor<RefundPaymentResponse> refundResponseCaptor;

    @Captor
    private ArgumentCaptor<Throwable> throwableCaptor;

    private PaymentGrpcService grpcService;

    @BeforeEach
    void setUp() {
        grpcService = new PaymentGrpcService(paymentService);
    }

    private ProcessPaymentRequest createProcessRequest(String orderId, String idempotencyKey,
                                                       String amount, String currency) {
        return ProcessPaymentRequest.newBuilder()
                .setOrderId(orderId)
                .setUserId("user-1")
                .setAmount(Money.newBuilder().setAmount(amount).setCurrency(currency).build())
                .setIdempotencyKey(idempotencyKey)
                .build();
    }

    private RefundPaymentRequest createRefundRequest(String paymentId, String orderId,
                                                     String amount, String reason) {
        return RefundPaymentRequest.newBuilder()
                .setPaymentId(paymentId)
                .setOrderId(orderId)
                .setAmount(Money.newBuilder().setAmount(amount).setCurrency("USD").build())
                .setReason(reason)
                .build();
    }

    @Nested
    @DisplayName("processPayment")
    class ProcessPaymentTests {

        @Test
        @DisplayName("should return success response when payment succeeds")
        void shouldReturnSuccessWhenPaymentSucceeds() {
            // given
            when(paymentService.processPayment(eq("order-1"), eq("user-1"),
                    any(BigDecimal.class), eq("USD"), eq("key-1")))
                    .thenReturn(new PaymentResult(true, "Payment processed successfully", "1", "txn-abc"));

            // when
            grpcService.processPayment(createProcessRequest("order-1", "key-1", "99.99", "USD"),
                    processResponseObserver);

            // then
            verify(processResponseObserver).onNext(processResponseCaptor.capture());
            verify(processResponseObserver).onCompleted();

            ProcessPaymentResponse response = processResponseCaptor.getValue();
            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getPaymentId()).isEqualTo("1");
            assertThat(response.getTransactionId()).isEqualTo("txn-abc");
        }

        @Test
        @DisplayName("should return FAILED_PRECONDITION when payment declined")
        void shouldReturnFailedPreconditionWhenDeclined() {
            // given
            when(paymentService.processPayment(anyString(), anyString(), any(), anyString(), anyString()))
                    .thenThrow(new PaymentDeclinedException("Card declined"));

            // when
            grpcService.processPayment(createProcessRequest("order-1", "key-1", "99.99", "USD"),
                    processResponseObserver);

            // then
            verify(processResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        }

        @Test
        @DisplayName("should return UNAVAILABLE when transient failure")
        void shouldReturnUnavailableWhenTransientFailure() {
            // given
            when(paymentService.processPayment(anyString(), anyString(), any(), anyString(), anyString()))
                    .thenThrow(new PaymentTransientException("Timeout"));

            // when
            grpcService.processPayment(createProcessRequest("order-1", "key-1", "99.99", "USD"),
                    processResponseObserver);

            // then
            verify(processResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        }

        @Test
        @DisplayName("should return INVALID_ARGUMENT when order_id is empty")
        void shouldReturnInvalidArgumentWhenOrderIdEmpty() {
            // when
            grpcService.processPayment(createProcessRequest("", "key-1", "99.99", "USD"),
                    processResponseObserver);

            // then
            verify(processResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(ex.getStatus().getDescription()).contains("order_id");
        }

        @Test
        @DisplayName("should return INVALID_ARGUMENT when idempotency_key is empty")
        void shouldReturnInvalidArgumentWhenIdempotencyKeyEmpty() {
            // when
            grpcService.processPayment(createProcessRequest("order-1", "", "99.99", "USD"),
                    processResponseObserver);

            // then
            verify(processResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(ex.getStatus().getDescription()).contains("idempotency_key");
        }

        @Test
        @DisplayName("should return INTERNAL on unexpected exception")
        void shouldReturnInternalOnUnexpectedException() {
            // given
            when(paymentService.processPayment(anyString(), anyString(), any(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Unexpected error"));

            // when
            grpcService.processPayment(createProcessRequest("order-1", "key-1", "99.99", "USD"),
                    processResponseObserver);

            // then
            verify(processResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("refundPayment")
    class RefundPaymentTests {

        @Test
        @DisplayName("should return success response when refund succeeds")
        void shouldReturnSuccessWhenRefundSucceeds() {
            // given
            when(paymentService.refundPayment(eq(1L), eq("order-1"), any(BigDecimal.class), eq("Customer request")))
                    .thenReturn(new PaymentResult(true, "Payment refunded successfully", "1", "refund-xyz"));

            // when
            grpcService.refundPayment(createRefundRequest("1", "order-1", "99.99", "Customer request"),
                    refundResponseObserver);

            // then
            verify(refundResponseObserver).onNext(refundResponseCaptor.capture());
            verify(refundResponseObserver).onCompleted();

            RefundPaymentResponse response = refundResponseCaptor.getValue();
            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getRefundTransactionId()).isEqualTo("refund-xyz");
        }

        @Test
        @DisplayName("should return NOT_FOUND when payment not found")
        void shouldReturnNotFoundWhenPaymentNotExists() {
            // given
            when(paymentService.refundPayment(eq(999L), anyString(), any(), anyString()))
                    .thenThrow(new ResourceNotFoundException("Payment", 999L));

            // when
            grpcService.refundPayment(createRefundRequest("999", "order-1", "99.99", "test"),
                    refundResponseObserver);

            // then
            verify(refundResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        @DisplayName("should return FAILED_PRECONDITION when payment state invalid")
        void shouldReturnFailedPreconditionWhenStateInvalid() {
            // given
            when(paymentService.refundPayment(eq(1L), anyString(), any(), anyString()))
                    .thenThrow(new IllegalStateException("Cannot refund"));

            // when
            grpcService.refundPayment(createRefundRequest("1", "order-1", "99.99", "test"),
                    refundResponseObserver);

            // then
            verify(refundResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        }

        @Test
        @DisplayName("should return INVALID_ARGUMENT when payment_id is empty")
        void shouldReturnInvalidArgumentWhenPaymentIdEmpty() {
            // when
            grpcService.refundPayment(createRefundRequest("", "order-1", "99.99", "test"),
                    refundResponseObserver);

            // then
            verify(refundResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("should return INVALID_ARGUMENT when payment_id is not a number")
        void shouldReturnInvalidArgumentWhenPaymentIdInvalid() {
            // when
            grpcService.refundPayment(createRefundRequest("abc", "order-1", "99.99", "test"),
                    refundResponseObserver);

            // then
            verify(refundResponseObserver).onError(throwableCaptor.capture());
            StatusRuntimeException ex = (StatusRuntimeException) throwableCaptor.getValue();
            assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }
}
