package com.robomart.order.saga.steps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.entity.Order;
import com.robomart.order.grpc.PaymentGrpcClient;
import com.robomart.order.grpc.PaymentServiceUnavailableException;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.SagaStep;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.proto.common.Money;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class ProcessPaymentStep implements SagaStep {

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentStep.class);

    private final PaymentGrpcClient paymentClient;

    public ProcessPaymentStep(PaymentGrpcClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    @Override
    public String getName() {
        return "ProcessPayment";
    }

    @Override
    public void execute(SagaContext context) {
        Order order = context.getOrder();
        log.info("Processing payment for orderId={}", order.getId());

        ProcessPaymentRequest request = ProcessPaymentRequest.newBuilder()
                .setOrderId(order.getId().toString())
                .setUserId(order.getUserId())
                .setAmount(Money.newBuilder()
                        .setCurrency("USD")
                        .setAmount(order.getTotalAmount().toPlainString())
                        .build())
                .setIdempotencyKey(order.getId().toString())
                .build();

        try {
            ProcessPaymentResponse response = paymentClient.processPayment(request);
            order.setPaymentId(response.getPaymentId());
            log.info("Payment processed for orderId={}, paymentId={}", order.getId(), response.getPaymentId());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                order.setCancellationReason("Payment declined");
                throw new SagaStepException("Payment declined for orderId=" + order.getId(), e, true);
            }
            order.setCancellationReason("Payment error: " + e.getStatus().getCode());
            throw new SagaStepException("Payment error for orderId=" + order.getId(), e, true);
        } catch (PaymentServiceUnavailableException e) {
            order.setCancellationReason("Payment service unavailable");
            throw new SagaStepException("Payment service circuit open for orderId=" + order.getId(), e, true);
        }
    }

    @Override
    public void compensate(SagaContext context) {
        // No-op: payment was not confirmed, no refund needed
        // Payment Service idempotency prevents duplicate charges on retry
        log.debug("ProcessPaymentStep.compensate() called — no-op (payment not confirmed)");
    }
}
