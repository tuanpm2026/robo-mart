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
import com.robomart.proto.payment.RefundPaymentRequest;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class RefundPaymentStep implements SagaStep {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentStep.class);

    private final PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    public RefundPaymentStep(PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub) {
        this.paymentStub = paymentStub;
    }

    @Override
    public String getName() {
        return "RefundPaymentStep";
    }

    @Override
    public void execute(SagaContext context) {
        Order order = context.getOrder();

        if (order.getPaymentId() == null) {
            log.warn("RefundPaymentStep.execute() skipped — no paymentId for orderId={}", order.getId());
            return;
        }

        String reason = order.getCancellationReason() != null
                ? order.getCancellationReason()
                : "Order cancelled";

        RefundPaymentRequest request = RefundPaymentRequest.newBuilder()
                .setPaymentId(order.getPaymentId())
                .setOrderId(order.getId().toString())
                .setAmount(Money.newBuilder()
                        .setCurrency("USD")
                        .setAmount(order.getTotalAmount().toPlainString())
                        .build())
                .setReason(reason)
                .build();

        try {
            var response = paymentStub.refundPayment(request);
            log.info("Payment refunded for orderId={}, refundTxId={}", order.getId(), response.getRefundTransactionId());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.warn("Payment not found for refund — skipping: paymentId={}, orderId={}",
                        order.getPaymentId(), order.getId());
                return;
            }
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                log.info("Refund already processed (idempotent) — treating as success: paymentId={}, orderId={}",
                        order.getPaymentId(), order.getId());
                return;
            }
            log.error("Failed to refund payment for orderId={}: {}", order.getId(), e.getMessage());
            throw new SagaStepException("Refund failed: " + e.getMessage(), false);
        }
    }

    @Override
    public void compensate(SagaContext context) {
        throw new UnsupportedOperationException("RefundPaymentStep is a forward-only step in cancellation saga");
    }
}
