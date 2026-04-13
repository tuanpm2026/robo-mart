package com.robomart.order.unit.saga.steps;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.grpc.PaymentGrpcClient;
import com.robomart.order.grpc.PaymentServiceUnavailableException;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.saga.steps.RefundPaymentStep;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefundPaymentStep")
class RefundPaymentStepTest {

    @Mock
    private PaymentGrpcClient paymentClient;

    private RefundPaymentStep step;

    @BeforeEach
    void setUp() {
        step = new RefundPaymentStep(paymentClient);
    }

    private Order buildOrder() {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 42L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId("user-1");
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(OrderStatus.PAYMENT_REFUNDING);
        order.setPaymentId("pay-123");
        order.setReservationId("res-456");
        order.setCancellationReason("Customer requested cancellation");
        order.setItems(new ArrayList<>());
        return order;
    }

    @Test
    @DisplayName("shouldSkipRefundWhenPaymentIdIsNull")
    void shouldSkipRefundWhenPaymentIdIsNull() {
        Order order = buildOrder();
        order.setPaymentId(null);
        SagaContext context = new SagaContext(order);

        assertThatNoException().isThrownBy(() -> step.execute(context));
        verify(paymentClient, never()).refundPayment(any());
    }

    @Test
    @DisplayName("shouldRefundSuccessfullyWhenPaymentIdPresent")
    void shouldRefundSuccessfullyWhenPaymentIdPresent() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentClient.refundPayment(any(RefundPaymentRequest.class)))
                .thenReturn(RefundPaymentResponse.newBuilder()
                        .setSuccess(true)
                        .setRefundTransactionId("refund-txn-789")
                        .build());

        assertThatNoException().isThrownBy(() -> step.execute(context));
        verify(paymentClient).refundPayment(any(RefundPaymentRequest.class));
    }

    @Test
    @DisplayName("shouldSkipGracefullyWhenPaymentNotFound")
    void shouldSkipGracefullyWhenPaymentNotFound() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentClient.refundPayment(any(RefundPaymentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.NOT_FOUND.withDescription("Payment not found")));

        assertThatNoException().isThrownBy(() -> step.execute(context));
    }

    @Test
    @DisplayName("shouldThrowSagaStepExceptionOnUnexpectedGrpcError")
    void shouldThrowSagaStepExceptionOnUnexpectedGrpcError() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentClient.refundPayment(any(RefundPaymentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Service down")));

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class);
    }

    @Test
    @DisplayName("shouldThrowSagaStepExceptionWhenCircuitOpen")
    void shouldThrowSagaStepExceptionWhenCircuitOpen() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentClient.refundPayment(any(RefundPaymentRequest.class)))
                .thenThrow(new PaymentServiceUnavailableException("Payment service unavailable",
                        new RuntimeException("Circuit breaker open")));

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class);
    }

    @Test
    @DisplayName("shouldThrowUnsupportedOperationOnCompensate")
    void shouldThrowUnsupportedOperationOnCompensate() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        assertThatThrownBy(() -> step.compensate(context))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
