package com.robomart.order.unit.saga.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.robomart.order.saga.steps.ProcessPaymentStep;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessPaymentStep")
class ProcessPaymentStepTest {

    @Mock
    private PaymentGrpcClient paymentClient;

    private ProcessPaymentStep step;

    @BeforeEach
    void setUp() {
        step = new ProcessPaymentStep(paymentClient);
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

        when(paymentClient.processPayment(any(ProcessPaymentRequest.class)))
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

        when(paymentClient.processPayment(any()))
                .thenThrow(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Payment declined")));

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class)
                .satisfies(e -> assertThat(((SagaStepException) e).isShouldCompensate()).isTrue());

        assertThat(order.getCancellationReason()).isEqualTo("Payment declined");
    }

    @Test
    @DisplayName("shouldThrowWithCompensationWhenCircuitOpen")
    void shouldThrowWithCompensationWhenCircuitOpen() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(paymentClient.processPayment(any()))
                .thenThrow(new PaymentServiceUnavailableException("Payment service unavailable",
                        new RuntimeException("Circuit breaker open")));

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class)
                .satisfies(e -> assertThat(((SagaStepException) e).isShouldCompensate()).isTrue());

        assertThat(order.getCancellationReason()).isEqualTo("Payment service unavailable");
    }

    @Test
    @DisplayName("shouldNotContainManualRetryLogic")
    void shouldNotContainManualRetryLogic() throws Exception {
        // Verify ProcessPaymentStep has no callWithRetry method (manual retry removed)
        var methods = ProcessPaymentStep.class.getDeclaredMethods();
        for (var method : methods) {
            assertThat(method.getName()).doesNotContain("callWithRetry");
        }
        // Verify no retry-related constants exist
        var fields = ProcessPaymentStep.class.getDeclaredFields();
        for (var field : fields) {
            assertThat(field.getName()).doesNotContainIgnoringCase("max_retries");
            assertThat(field.getName()).doesNotContainIgnoringCase("initial_delay");
            assertThat(field.getName()).doesNotContainIgnoringCase("backoff_multiplier");
        }
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
