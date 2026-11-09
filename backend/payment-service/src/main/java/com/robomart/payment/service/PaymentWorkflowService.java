package com.robomart.payment.service;

import com.robomart.payment.enums.PaymentStatus;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.proto.saga.ChargeOperation;
import com.robomart.proto.saga.ChargeOutcome;
import com.robomart.proto.saga.RefundOperation;
import com.robomart.proto.saga.RefundOutcome;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentWorkflowService {
    private final MockGatewayLedger provider;
    private final PaymentIntentStore intents;
    private final PaymentRepository payments;
    private final PaymentService service;
    private final com.robomart.payment.repository.RefundIntentRepository refunds;

    public PaymentWorkflowService(
            MockGatewayLedger provider,
            PaymentIntentStore intents,
            PaymentRepository payments,
            PaymentService service,
            com.robomart.payment.repository.RefundIntentRepository refunds) {
        this.provider = provider;
        this.intents = intents;
        this.payments = payments;
        this.service = service;
        this.refunds = refunds;
    }

    public ChargeOutcome charge(ChargeOperation request, boolean close) {
        validate(request.getOrderId());
        validate(request.getUserId());
        validate(request.getIdempotencyKey());
        BigDecimal amount = new BigDecimal(request.getAmount());
        if (amount.signum() <= 0
                || amount.scale() > 2
                || !request.getCurrency().matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid charge amount/currency");
        }
        var receipt =
                provider.chargeOutcome(
                        request.getOrderId(),
                        request.getIdempotencyKey(),
                        amount,
                        request.getCurrency(),
                        request.getUserId(),
                        close);
        if ("SUCCEEDED".equals(receipt.state())) {
            intents.reconcileCharge(
                    request.getOrderId(),
                    request.getIdempotencyKey(),
                    request.getUserId(),
                    amount,
                    request.getCurrency(),
                    receipt.transactionId());
        }
        var payment = payments.findByOrderId(request.getOrderId());
        var result =
                ChargeOutcome.newBuilder()
                        .setState(receipt.state())
                        .setAmount(amount.toPlainString())
                        .setCurrency(request.getCurrency());
        if (payment.isPresent()) {
            var p = payment.get();
            if (!request.getIdempotencyKey().equals(p.getIdempotencyKey())
                    || !request.getUserId().equals(p.getUserId())
                    || amount.compareTo(p.getAmount()) != 0
                    || !request.getCurrency().equals(p.getCurrency())) {
                throw new IllegalArgumentException("Payment request binding mismatch");
            }
            if (p.getStatus() == PaymentStatus.COMPLETED
                    || p.getStatus() == PaymentStatus.REFUNDED) {
                result.setState("SUCCEEDED").setPaymentId(p.getId().toString());
            }
            if ("UNKNOWN".equals(receipt.state())
                    && p.getStatus() == PaymentStatus.PROCESSING
                    && p.getProcessingUntil() != null
                    && p.getProcessingUntil().isAfter(java.time.Instant.now()))
                result.setState("IN_PROGRESS");
            if (p.getRefundedAmount() != null)
                result.setRefundedAmount(p.getRefundedAmount().toPlainString());
        }
        return result.build();
    }

    public RefundOutcome refund(RefundOperation request, boolean execute) {
        validate(request.getOrderId());
        validate(request.getIdempotencyKey());
        var payment =
                payments.findByOrderId(request.getOrderId())
                        .orElseThrow(
                                () -> new IllegalStateException("Charge outcome not resolved"));
        if (!payment.getId().toString().equals(request.getPaymentId())) {
            throw new IllegalArgumentException("Payment does not belong to order");
        }
        var intent =
                refunds.findByPaymentIdAndIdempotencyKey(
                        payment.getId(), request.getIdempotencyKey());
        if (intent.isPresent() && !"COMPLETED".equals(intent.get().getStatus())) {
            var receipt =
                    provider.refundReceipt(
                            payment.getTransactionId(),
                            request.getIdempotencyKey(),
                            intent.get().getAmount());
            if (receipt.isPresent()) {
                intents.reconcileRefund(
                        request.getOrderId(),
                        request.getIdempotencyKey(),
                        intent.get().getAmount(),
                        receipt.get());
                payment = payments.findByOrderId(request.getOrderId()).orElseThrow();
            }
        }
        if (execute && payment.getStatus() != PaymentStatus.REFUNDED) {
            service.refundCancellation(request.getOrderId(), request.getIdempotencyKey());
            payment = payments.findByOrderId(request.getOrderId()).orElseThrow();
        }
        return RefundOutcome.newBuilder()
                .setState(
                        payment.getStatus() == PaymentStatus.REFUNDED
                                        && payment.getRefundedAmount() != null
                                        && payment.getAmount()
                                                        .compareTo(payment.getRefundedAmount())
                                                == 0
                                ? "SUCCEEDED"
                                : "UNKNOWN")
                .setAmount(payment.getAmount().toPlainString())
                .build();
    }

    private static void validate(String value) {
        if (value == null || value.isBlank() || value.length() > 100)
            throw new IllegalArgumentException("Invalid operation identity");
    }
}
