package com.robomart.saga;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.robomart.common.saga.CheckoutSnapshot;
import com.robomart.proto.inventory.*;
import com.robomart.proto.payment.*;
import com.robomart.proto.saga.*;

import io.grpc.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class SagaEngineTest {
    SagaStore store = mock(SagaStore.class);
    SagaParticipants port = mock(SagaParticipants.class);
    SagaEngine engine = new SagaEngine(store, port);
    UUID id = UUID.randomUUID();

    Work work(String state, boolean cancel) {
        return new Work(
                id,
                new CheckoutSnapshot(
                        "42",
                        id.toString(),
                        1,
                        "user",
                        "20.00",
                        "USD",
                        1,
                        List.of(new CheckoutSnapshot.Line("1", 2))),
                state,
                cancel,
                "",
                "res",
                "9",
                "SUCCEEDED",
                1,
                Instant.now(),
                UUID.randomUUID());
    }

    @BeforeEach
    void allowIntents() {
        when(store.intent(any(), anyString(), any())).thenReturn(true);
    }

    @Test
    void reservePricedSnapshot() {
        when(port.order(any()))
                .thenReturn(WorkflowOrderResponse.newBuilder().setStatus("PENDING").build());
        when(port.reserve(any()))
                .thenReturn(
                        ReserveInventoryResponse.newBuilder()
                                .setSuccess(true)
                                .setReservationId("saved")
                                .build());
        assertThat(engine.run(work("RESERVE", false)).reservationId()).isEqualTo("saved");
        verify(port)
                .reserve(
                        argThat(
                                q ->
                                        q.getItems(0).getQuantity() == 2
                                                && q.getOrderId().equals("42")));
    }

    @Test
    void adoptionReconcilesPaidOrderWithoutAnotherReserveOrCharge() {
        when(port.order(any()))
                .thenReturn(
                        WorkflowOrderResponse.newBuilder()
                                .setStatus("CONFIRMED")
                                .setReservationId("original-res")
                                .setPaymentId("9")
                                .build());
        when(port.charge(any(), eq(false)))
                .thenReturn(
                        ChargeOutcome.newBuilder().setState("SUCCEEDED").setPaymentId("9").build());
        assertThat(engine.run(work("RESERVE", false)).state()).isEqualTo("CHECKOUT_CONFIRMED");
        verify(port, never()).reserve(any());
        verify(port, never()).pay(any());
    }

    @Test
    void inventoryBusinessFailureBeginsCompensation() {
        when(port.order(any()))
                .thenReturn(WorkflowOrderResponse.newBuilder().setStatus("PENDING").build());
        when(port.reserve(any())).thenThrow(Status.FAILED_PRECONDITION.asRuntimeException());
        assertThat(engine.run(work("RESERVE", false)).state()).isEqualTo("BEGIN_CANCEL");
        verify(port, never()).pay(any());
    }

    @Test
    void inventoryTimeoutRemainsUnknown() {
        when(port.order(any()))
                .thenReturn(WorkflowOrderResponse.newBuilder().setStatus("PENDING").build());
        when(port.reserve(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
        assertThatThrownBy(() -> engine.run(work("RESERVE", false)))
                .isInstanceOf(io.grpc.StatusRuntimeException.class);
        verify(port, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "CANCELLATION_PENDING"})
    void staleStartCannotReserveCancelledOrder(String state) {
        when(port.order(any()))
                .thenReturn(WorkflowOrderResponse.newBuilder().setStatus(state).build());
        assertThat(engine.run(work("RESERVE", false)).state()).isEqualTo("BEGIN_CANCEL");
        verify(port, never()).reserve(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVE", "CHARGE", "CONFIRM", "CHECKOUT_CONFIRMED"})
    void cancellationPreemptsForwardWork(String state) {
        assertThat(engine.run(work(state, true)).state()).isEqualTo("BEGIN_CANCEL");
        verifyNoInteractions(port);
    }

    @Test
    void chargeUsesStableOrderKeyAndReconcilesReceipt() {
        when(port.charge(any(), eq(false)))
                .thenReturn(
                        ChargeOutcome.newBuilder().setState("UNKNOWN").build(),
                        ChargeOutcome.newBuilder().setState("SUCCEEDED").setPaymentId("9").build());
        when(port.pay(any()))
                .thenReturn(ProcessPaymentResponse.newBuilder().setSuccess(true).build());
        assertThat(engine.run(work("CHARGE", false)).state()).isEqualTo("CONFIRM");
        verify(port)
                .pay(
                        argThat(
                                q ->
                                        q.getIdempotencyKey().equals("42")
                                                && q.getAmount().getAmount().equals("20.00")));
    }

    @Test
    void durableChargeReceiptAvoidsSecondCharge() {
        when(port.charge(any(), eq(false)))
                .thenReturn(
                        ChargeOutcome.newBuilder().setState("SUCCEEDED").setPaymentId("9").build());
        assertThat(engine.run(work("CHARGE", false)).paymentId()).isEqualTo("9");
        verify(port, never()).pay(any());
    }

    @Test
    void definitiveDeclineCancels() {
        when(port.charge(any(), eq(false)))
                .thenReturn(ChargeOutcome.newBuilder().setState("DECLINED_FINAL").build());
        assertThat(engine.run(work("CHARGE", false)).state()).isEqualTo("BEGIN_CANCEL");
    }

    @Test
    void unknownChargeNeverReleasesInventory() {
        when(port.charge(any(), eq(false)))
                .thenReturn(ChargeOutcome.newBuilder().setState("IN_PROGRESS").build());
        assertThatThrownBy(() -> engine.run(work("CHARGE", false)))
                .hasMessageContaining("unresolved");
        verify(port, never()).release(any());
    }

    @Test
    void confirmationReceiptClosesCheckout() {
        when(port.command(any()))
                .thenReturn(
                        OrderWorkflowResult.newBuilder()
                                .setApplied(true)
                                .setStatus("CONFIRMED")
                                .build());
        assertThat(engine.run(work("CONFIRM", false)).state()).isEqualTo("CHECKOUT_CONFIRMED");
        verify(port)
                .command(
                        argThat(
                                q ->
                                        q.getCommandId().equals(id + ":CONFIRM")
                                                && q.getPaymentId().equals("9")));
    }

    @Test
    void cancellationWinsConfirmRace() {
        when(port.command(any()))
                .thenReturn(
                        OrderWorkflowResult.newBuilder().setStatus("CANCELLATION_PENDING").build());
        assertThat(engine.run(work("CONFIRM", false)).state()).isEqualTo("BEGIN_CANCEL");
    }

    @Test
    void shippingWinsCancellationRaceWithoutRefund() {
        when(port.command(any()))
                .thenReturn(OrderWorkflowResult.newBuilder().setStatus("SHIPPED").build());
        assertThat(engine.run(work("BEGIN_CANCEL", true)).state()).isEqualTo("CANCEL_REJECTED");
        verify(port, never()).refund(any(), anyBoolean());
    }

    @Test
    void cancellationAdmissionPrecedesClosingCharge() {
        when(port.command(any()))
                .thenReturn(OrderWorkflowResult.newBuilder().setApplied(true).build());
        assertThat(engine.run(work("BEGIN_CANCEL", true)).state()).isEqualTo("CLOSE_CHARGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_CHARGE_FINAL", "DECLINED_FINAL"})
    void onlyTerminalNoChargeAllowsRelease(String state) {
        when(port.charge(any(), eq(true)))
                .thenReturn(ChargeOutcome.newBuilder().setState(state).build());
        assertThat(engine.run(work("CLOSE_CHARGE", true)).state()).isEqualTo("RELEASE");
    }

    @Test
    void unknownClosureRetainsStock() {
        when(port.charge(any(), eq(true)))
                .thenReturn(ChargeOutcome.newBuilder().setState("UNKNOWN").build());
        assertThatThrownBy(() -> engine.run(work("CLOSE_CHARGE", true)))
                .hasMessageContaining("inventory retained");
        verify(port, never()).release(any());
    }

    @Test
    void paidCancellationRequiresRefund() {
        when(port.charge(any(), eq(true)))
                .thenReturn(
                        ChargeOutcome.newBuilder()
                                .setState("SUCCEEDED")
                                .setPaymentId("9")
                                .setRefundedAmount("5.00")
                                .build());
        assertThat(engine.run(work("CLOSE_CHARGE", true)).state()).isEqualTo("REFUND");
    }

    @Test
    void alreadyRefundedPaymentCanRelease() {
        when(port.charge(any(), eq(true)))
                .thenReturn(
                        ChargeOutcome.newBuilder()
                                .setState("SUCCEEDED")
                                .setPaymentId("9")
                                .setRefundedAmount("20.00")
                                .build());
        assertThat(engine.run(work("CLOSE_CHARGE", true)).resolution()).isEqualTo("REFUNDED");
    }

    @Test
    void refundUnknownKeepsStockAndReusesKey() {
        when(port.refund(any(), anyBoolean()))
                .thenReturn(RefundOutcome.newBuilder().setState("UNKNOWN").build());
        assertThatThrownBy(() -> engine.run(work("REFUND", true)))
                .hasMessageContaining("inventory retained");
        verify(port).refund(argThat(q -> q.getIdempotencyKey().equals(id + ":refund")), eq(true));
        verify(port, never()).release(any());
    }

    @Test
    void refundConfirmedThenReleaseThenFinalCancel() {
        when(port.refund(any(), eq(false)))
                .thenReturn(RefundOutcome.newBuilder().setState("SUCCEEDED").build());
        assertThat(engine.run(work("REFUND", true)).resolution()).isEqualTo("REFUNDED");
        verify(port, never()).refund(any(), eq(true));
        when(port.release(any()))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());
        assertThat(engine.run(work("RELEASE", true)).state()).isEqualTo("FINALIZE_CANCEL");
        when(port.command(any()))
                .thenReturn(OrderWorkflowResult.newBuilder().setApplied(true).build());
        assertThat(engine.run(work("FINALIZE_CANCEL", true)).state()).isEqualTo("CANCELLED");
        verify(port).command(argThat(OrderWorkflowCommand::getInventoryClosed));
    }

    @Test
    void expiredClaimCannotDispatch() {
        when(store.intent(any(), anyString(), any())).thenReturn(false);
        assertThatThrownBy(() -> engine.run(work("BEGIN_CANCEL", true)))
                .hasMessageContaining("claim expired");
        verifyNoInteractions(port);
    }
}
