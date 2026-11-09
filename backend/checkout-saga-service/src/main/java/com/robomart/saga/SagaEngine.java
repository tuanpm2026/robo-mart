package com.robomart.saga;

import com.google.protobuf.MessageLite;
import com.robomart.proto.common.Money;
import com.robomart.proto.inventory.*;
import com.robomart.proto.payment.*;
import com.robomart.proto.saga.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

@Service
public class SagaEngine {
    private final SagaStore store;
    private final SagaParticipants participants;

    public SagaEngine(SagaStore store, SagaParticipants participants) {
        this.store = store;
        this.participants = participants;
    }

    public Transition run(Work w) {
        if (w.cancelRequested()
                && List.of("RESERVE", "CHARGE", "CONFIRM", "CHECKOUT_CONFIRMED")
                        .contains(w.state()))
            return Transition.cancel(w, "Customer requested cancellation");
        return switch (w.state()) {
            case "RESERVE" -> reserve(w);
            case "CHARGE" -> charge(w);
            case "CONFIRM" -> confirm(w);
            case "CHECKOUT_CONFIRMED" -> Transition.next(w, "CHECKOUT_CONFIRMED");
            case "BEGIN_CANCEL" -> beginCancel(w);
            case "CLOSE_CHARGE" -> close(w);
            case "REFUND" -> refund(w);
            case "RELEASE" -> release(w);
            case "FINALIZE_CANCEL" -> finalizeCancel(w);
            default -> throw new IllegalStateException("Unsupported workflow state " + w.state());
        };
    }

    private Transition reserve(Work w) {
        var order = participants.order(orderRequest(w));
        if (List.of("CANCELLATION_PENDING", "CANCELLED").contains(order.getStatus()))
            return Transition.cancel(w, order.getCancellationReason());
        if (List.of("CONFIRMED", "SHIPPED", "DELIVERED").contains(order.getStatus())) {
            // Reconcile an explicit legacy handover (or a replay after confirmation), never run
            // checkout again.
            var receipt = participants.charge(chargeRequest(w), false);
            if (!"SUCCEEDED".equals(receipt.getState())
                    || order.getPaymentId().isBlank()
                    || order.getReservationId().isBlank()
                    || !order.getPaymentId().equals(receipt.getPaymentId()))
                throw new IllegalStateException("Handover receipts require review");
            return new Transition(
                    "CHECKOUT_CONFIRMED",
                    order.getReservationId(),
                    order.getPaymentId(),
                    "SUCCEEDED",
                    w.reason());
        }
        if (!"PENDING".equals(order.getStatus()))
            throw new IllegalStateException("Order is no longer pending");
        var builder = ReserveInventoryRequest.newBuilder().setOrderId(w.snapshot().orderId());
        w.snapshot()
                .items()
                .forEach(
                        i ->
                                builder.addItems(
                                        ReservationItem.newBuilder()
                                                .setProductId(i.productId())
                                                .setQuantity(i.quantity())));
        var q = builder.build();
        try {
            var result = dispatch(w, "reserve", q, () -> participants.reserve(q));
            if (!result.getSuccess()) return Transition.cancel(w, "Inventory rejected reservation");
            return new Transition(
                    "CHARGE",
                    result.getReservationId(),
                    w.paymentId(),
                    w.chargeResolution(),
                    w.reason());
        } catch (StatusRuntimeException e) {
            if (List.of(Status.Code.FAILED_PRECONDITION, Status.Code.NOT_FOUND)
                    .contains(e.getStatus().getCode()))
                return Transition.cancel(w, "Inventory rejected reservation");
            throw e;
        }
    }

    private Transition charge(Work w) {
        var q = chargeRequest(w);
        ChargeOutcome result = participants.charge(q, false);
        if ("UNKNOWN".equals(result.getState())) {
            var pay =
                    ProcessPaymentRequest.newBuilder()
                            .setOrderId(q.getOrderId())
                            .setUserId(q.getUserId())
                            .setIdempotencyKey(q.getIdempotencyKey())
                            .setAmount(
                                    Money.newBuilder()
                                            .setAmount(q.getAmount())
                                            .setCurrency(q.getCurrency()))
                            .build();
            // A lost response is reconciled from the provider receipt on the next attempt.
            try {
                dispatch(w, "charge", pay, () -> participants.pay(pay));
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.FAILED_PRECONDITION) throw e;
            }
            result = participants.charge(q, false);
        }
        if ("SUCCEEDED".equals(result.getState()))
            return new Transition(
                    "CONFIRM", w.reservationId(), result.getPaymentId(), "SUCCEEDED", w.reason());
        if (List.of("DECLINED_FINAL", "NO_CHARGE_FINAL").contains(result.getState()))
            return Transition.cancel(w, "Payment declined");
        throw new IllegalStateException("Charge outcome is unresolved");
    }

    private Transition confirm(Work w) {
        var q = command(w, "CONFIRM").build();
        var result = dispatch(w, "CONFIRM", q, () -> participants.command(q));
        if (result.getApplied()) return Transition.next(w, "CHECKOUT_CONFIRMED");
        if (List.of("CANCELLATION_PENDING", "CANCELLED").contains(result.getStatus()))
            return Transition.cancel(w, "Cancellation won confirmation");
        throw new IllegalStateException("Confirmation precondition rejected");
    }

    private Transition beginCancel(Work w) {
        var q = command(w, "BEGIN_CANCEL").build();
        var result = dispatch(w, "BEGIN_CANCEL", q, () -> participants.command(q));
        if (result.getApplied()) return Transition.next(w, "CLOSE_CHARGE");
        if (List.of("SHIPPED", "DELIVERED").contains(result.getStatus()))
            return Transition.next(w, "CANCEL_REJECTED");
        throw new IllegalStateException("Cancellation precondition rejected");
    }

    private Transition close(Work w) {
        var q = chargeRequest(w);
        var result = dispatch(w, "close-charge", q, () -> participants.charge(q, true));
        if ("SUCCEEDED".equals(result.getState())) {
            boolean refunded =
                    !result.getRefundedAmount().isBlank()
                            && new BigDecimal(result.getRefundedAmount())
                                            .compareTo(new BigDecimal(q.getAmount()))
                                    == 0;
            return new Transition(
                    refunded ? "RELEASE" : "REFUND",
                    w.reservationId(),
                    result.getPaymentId(),
                    refunded ? "REFUNDED" : "SUCCEEDED",
                    w.reason());
        }
        if (List.of("DECLINED_FINAL", "NO_CHARGE_FINAL").contains(result.getState()))
            return new Transition(
                    "RELEASE", w.reservationId(), w.paymentId(), result.getState(), w.reason());
        throw new IllegalStateException("Charge closure is unresolved; inventory retained");
    }

    private Transition refund(Work w) {
        var q =
                RefundOperation.newBuilder()
                        .setOrderId(w.snapshot().orderId())
                        .setPaymentId(w.paymentId())
                        .setIdempotencyKey(w.key("refund"))
                        .build();
        var result = participants.refund(q, false);
        if (!"SUCCEEDED".equals(result.getState()))
            result = dispatch(w, "refund", q, () -> participants.refund(q, true));
        if (!"SUCCEEDED".equals(result.getState()))
            throw new IllegalStateException("Refund unresolved; inventory retained");
        return new Transition("RELEASE", w.reservationId(), w.paymentId(), "REFUNDED", w.reason());
    }

    private Transition release(Work w) {
        var q =
                ReleaseInventoryRequest.newBuilder()
                        .setOrderId(w.snapshot().orderId())
                        .setReservationId(w.reservationId())
                        .build();
        if (!dispatch(w, "release", q, () -> participants.release(q)).getSuccess())
            throw new IllegalStateException("Inventory release unresolved");
        return Transition.next(w, "FINALIZE_CANCEL");
    }

    private Transition finalizeCancel(Work w) {
        var q = command(w, "FINALIZE_CANCEL").setInventoryClosed(true).build();
        if (!dispatch(w, "FINALIZE_CANCEL", q, () -> participants.command(q)).getApplied())
            throw new IllegalStateException("Final cancellation rejected");
        return Transition.next(w, "CANCELLED");
    }

    private WorkflowOrderRequest orderRequest(Work w) {
        return WorkflowOrderRequest.newBuilder()
                .setOrderId(w.snapshot().orderId())
                .setSagaId(w.id().toString())
                .setOwnerEpoch(w.snapshot().epoch())
                .build();
    }

    private ChargeOperation chargeRequest(Work w) {
        var s = w.snapshot();
        return ChargeOperation.newBuilder()
                .setOrderId(s.orderId())
                .setIdempotencyKey(s.orderId())
                .setUserId(s.userId())
                .setAmount(s.amount())
                .setCurrency(s.currency())
                .build();
    }

    private OrderWorkflowCommand.Builder command(Work w, String action) {
        var q =
                OrderWorkflowCommand.newBuilder()
                        .setOrderId(w.snapshot().orderId())
                        .setSagaId(w.id().toString())
                        .setOwnerEpoch(w.snapshot().epoch())
                        .setCommandId(w.key(action))
                        .setAction(action);
        // Each command has a fixed minimal payload; later cancellation requests cannot mutate a
        // prior intent.
        if ("CONFIRM".equals(action))
            q.setReservationId(w.reservationId()).setPaymentId(w.paymentId());
        else if ("FINALIZE_CANCEL".equals(action))
            q.setReservationId(w.reservationId())
                    .setPaymentId(w.paymentId())
                    .setChargeResolution(w.chargeResolution());
        return q;
    }

    private <T> T dispatch(Work w, String op, MessageLite q, Supplier<T> call) {
        if (!store.intent(w, op, q.toByteArray()))
            throw new IllegalStateException("Workflow claim expired");
        return call.get();
    }
}
