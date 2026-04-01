package com.robomart.order.unit.saga.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.saga.steps.ProcessPaymentStep;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessPaymentStep")
class ProcessPaymentStepTest {

    @Mock
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    private ProcessPaymentStep step;

    @BeforeEach
    void setUp() {
        step = new ProcessPaymentStep(paymentStub);
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
        order.setStatus(OrderStatus.PAYMENT_PROCESSING);
        order.setItems(new ArrayList<>());
        return order;
    }

    @Test
    @DisplayName("shouldSetPaymentIdWhenPaymentSucceeds")
    void shouldSetPaymentIdWhenPaymentSucceeds() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenReturn(ProcessPaymentResponse.newBuilder()
                        .setSuccess(true)
                        .setPaymentId("pay-999")
                        .setTransactionId("txn-abc")
                        .build());

        step.execute(context);

        assertThat(order.getPaymentId()).isEqualTo("pay-999");
    }

    @Test
    @DisplayName("shouldThrowWithCompensationWhenPaymentDeclined")
    void shouldThrowWithCompensationWhenPaymentDeclined() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentStub.processPayment(any()))
                .thenThrow(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Payment declined")));

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class)
                .satisfies(e -> assertThat(((SagaStepException) e).isShouldCompensate()).isTrue());

        assertThat(order.getCancellationReason()).isEqualTo("Payment declined");
    }

    @Test
    @DisplayName("shouldRetryThreeTimesOnTransientFailureThenThrow")
    void shouldRetryThreeTimesOnTransientFailureThenThrow() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        // Override step with shorter delays for testing
        ProcessPaymentStep testStep = new ProcessPaymentStep(paymentStub) {
            // Use reflection-free approach: step delays are in callWithRetry which uses Thread.sleep
            // In unit test we use UNAVAILABLE which causes retries
        };

        when(paymentStub.processPayment(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Transient error")));

        // This will sleep 1s+2s=3s total — reduce by testing with mock that fails fast
        // We mock the stub to always return UNAVAILABLE, but we want to verify 3 attempts
        // Note: this test will take ~3 seconds due to actual retry backoff
        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class)
                .satisfies(e -> assertThat(((SagaStepException) e).isShouldCompensate()).isTrue());

        assertThat(order.getCancellationReason()).isEqualTo("Payment service unavailable");
        verify(paymentStub, times(3)).processPayment(any());
    }

    @Test
    @DisplayName("shouldSucceedOnSecondAttemptAfterTransientFailure")
    void shouldSucceedOnSecondAttemptAfterTransientFailure() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentStub.processPayment(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Transient")))
                .thenReturn(ProcessPaymentResponse.newBuilder()
                        .setSuccess(true)
                        .setPaymentId("pay-retry-success")
                        .build());

        step.execute(context);

        assertThat(order.getPaymentId()).isEqualTo("pay-retry-success");
        verify(paymentStub, times(2)).processPayment(any());
    }

    @Test
    @DisplayName("shouldReturnNameProcessPayment")
    void shouldReturnNameProcessPayment() {
        assertThat(step.getName()).isEqualTo("ProcessPayment");
    }

    @Test
    @DisplayName("shouldBeNoOpOnCompensate")
    void shouldBeNoOpOnCompensate() {
        step.compensate(new SagaContext(buildOrder()));
    }
}
