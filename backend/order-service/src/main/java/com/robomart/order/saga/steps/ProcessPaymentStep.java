package com.robomart.order.saga.steps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.entity.Order;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.SagaStep;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.proto.common.Money;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class ProcessPaymentStep implements SagaStep {

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentStep.class);

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_DELAY_MS = 1000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    public ProcessPaymentStep(PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub) {
        this.paymentStub = paymentStub;
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

        ProcessPaymentResponse response = callWithRetry(request, order);
        order.setPaymentId(response.getPaymentId());
        log.info("Payment processed for orderId={}, paymentId={}", order.getId(), response.getPaymentId());
    }

    private ProcessPaymentResponse callWithRetry(ProcessPaymentRequest request, Order order) {
        long delayMs = INITIAL_DELAY_MS;
        StatusRuntimeException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return paymentStub.processPayment(request);
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                    lastException = e;
                    log.warn("Payment transient failure for orderId={}, attempt={}/{}", order.getId(), attempt, MAX_RETRIES);
                    if (attempt < MAX_RETRIES) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new SagaStepException("Payment retry interrupted for orderId=" + order.getId(), ie, true);
                        }
                        delayMs = (long) (delayMs * BACKOFF_MULTIPLIER);
                    }
                } else if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                    order.setCancellationReason("Payment declined");
                    throw new SagaStepException("Payment declined for orderId=" + order.getId(), e, true);
                } else {
                    order.setCancellationReason("Payment error: " + e.getStatus().getCode());
                    throw new SagaStepException("Payment error for orderId=" + order.getId(), e, true);
                }
            }
        }

        // All retries exhausted
        order.setCancellationReason("Payment service unavailable");
        throw new SagaStepException("Payment service unavailable after " + MAX_RETRIES + " retries for orderId=" + order.getId(), lastException, true);
    }

    @Override
    public void compensate(SagaContext context) {
        // No-op: payment was not confirmed, no refund needed
        // Payment Service idempotency prevents duplicate charges on retry
        log.debug("ProcessPaymentStep.compensate() called — no-op (payment not confirmed)");
    }
}
