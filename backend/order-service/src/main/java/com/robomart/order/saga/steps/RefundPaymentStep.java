package com.robomart.order.saga.steps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.entity.Order;
import com.robomart.order.grpc.PaymentBusinessException;
import com.robomart.order.grpc.PaymentGrpcClient;
import com.robomart.order.grpc.PaymentServiceUnavailableException;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.SagaStep;
import com.robomart.order.saga.SagaStepResult;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.proto.common.Money;
import com.robomart.proto.payment.RefundPaymentRequest;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class RefundPaymentStep implements SagaStep {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentStep.class);

    private final PaymentGrpcClient paymentClient;

    public RefundPaymentStep(PaymentGrpcClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    @Override
    public String getName() {
        return "RefundPaymentStep";
    }

    @Override
    public SagaStepResult execute(SagaContext context) {
        Order order = context.getOrder();

        if (order.getPaymentId() == null) {
            log.warn("RefundPaymentStep.execute() skipped — no paymentId for orderId={}", order.getId());
            return SagaStepResult.NONE;
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
            var response = paymentClient.refundPayment(request);
            if (!response.getSuccess()) {
                throw new SagaStepException("Refund was not acknowledged", false);
            }
            log.info("Payment refunded for orderId={}, refundTxId={}",
                    order.getId(), response.getRefundTransactionId());
            return SagaStepResult.NONE;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                log.info("Refund already processed (idempotent) — treating as success: paymentId={}, orderId={}",
                        order.getPaymentId(), order.getId());
                return SagaStepResult.NONE;
            }
            log.error("Failed to refund payment for orderId={}: {}", order.getId(), e.getMessage());
            throw new SagaStepException("Refund failed: " + e.getMessage(), false);
        } catch (PaymentBusinessException e) {
            if (e.getStatusCode() == Status.Code.ALREADY_EXISTS) {
                log.info("Refund already processed for orderId={}", order.getId());
                return SagaStepResult.NONE;
            }
            // NOT_FOUND cannot prove that a known charge was refunded; keep it recoverable.
            log.error("Payment refund rejected (business) for orderId={}: {}", order.getId(), e.getMessage());
            throw new SagaStepException("Refund rejected: " + e.getMessage(), e, false);
        } catch (PaymentServiceUnavailableException e) {
            log.error("Payment service unavailable during refund for orderId={}: {}", order.getId(), e.getMessage());
            throw new SagaStepException("Refund failed — payment service circuit open: " + e.getMessage(), e, false);
        }
    }

    @Override
    public void compensate(SagaContext context) {
        throw new UnsupportedOperationException("RefundPaymentStep is a forward-only step in cancellation saga");
    }
}
