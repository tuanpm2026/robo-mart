package com.robomart.notification.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import com.robomart.common.dto.ApiResponse;
import com.robomart.notification.client.AdminReadModels.InventoryItem;
import com.robomart.notification.client.AdminReadModels.InventorySummary;
import com.robomart.notification.client.AdminReadModels.OrderItem;
import com.robomart.notification.client.AdminReadModels.OrderPayment;
import com.robomart.notification.client.AdminReadModels.OrderSummary;
import com.robomart.notification.client.AdminReadModels.PaymentSummary;
import com.robomart.notification.client.InventoryAdminReadClient;
import com.robomart.notification.client.OrderAdminReadClient;
import com.robomart.notification.client.PaymentAdminReadClient;
import com.robomart.notification.service.AdminPushService;
import com.robomart.notification.service.ReconciliationService;

class ReconciliationServiceTest {
    private final InventoryAdminReadClient inventory = mock(InventoryAdminReadClient.class);
    private final PaymentAdminReadClient payments = mock(PaymentAdminReadClient.class);
    private final OrderAdminReadClient orders = mock(OrderAdminReadClient.class);
    private final AdminPushService push = mock(AdminPushService.class);
    private final ReconciliationService service = new ReconciliationService(inventory, payments, orders, push);

    private void stock(int reserved) {
        when(inventory.reconciliation()).thenReturn(new ApiResponse<>(new InventorySummary(
                List.of(new InventoryItem(1L, 2000 - reserved, reserved, 2000)), Instant.now()), null));
    }
    private OrderSummary order(String id, String status, int quantity) {
        return new OrderSummary(id, status, List.of(new OrderItem(1L, quantity)));
    }
    private void paymentPage(int page, boolean next, OrderPayment... values) {
        when(payments.reconciliation(page, 100)).thenReturn(new ApiResponse<>(
                new PaymentSummary(List.of(values), Instant.now(), page, 100, next), null));
    }

    @Test
    void initialStatusDoesNotClaimReconciliationAlreadyRan() {
        assertThat(service.getLastInventoryResult().status()).isEqualTo("NOT_RUN");
        assertThat(service.getLastPaymentResult().status()).isEqualTo("NOT_RUN");
    }

    @Test
    void confirmedStockIsCommittedWhilePendingAndReleasingStockRemainsReserved() {
        stock(10);
        when(orders.reconciliation()).thenReturn(new ApiResponse<>(List.of(order("1", "CONFIRMED", 20),
                order("2", "PAYMENT_PENDING", 6), order("3", "INVENTORY_RELEASING", 4)), null));
        var result = service.runInventoryReconciliation();
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.hasDiscrepancies()).isFalse();
        verifyNoInteractions(push);
    }

    @Test
    void mismatchedReservedUnitsGenerateAnAlert() {
        stock(15);
        when(orders.reconciliation()).thenReturn(new ApiResponse<>(List.of(order("1", "PAYMENT_PENDING", 10)), null));
        var result = service.runInventoryReconciliation();
        assertThat(result.hasDiscrepancies()).isTrue();
        assertThat(result.discrepancies()).hasSize(1);
        verify(push).pushReconciliationAlert(result);
    }

    @Test
    void smallDifferenceBelowBothConfiguredThresholdsIsClean() {
        stock(1002);
        when(orders.reconciliation()).thenReturn(new ApiResponse<>(List.of(order("1", "PAYMENT_PENDING", 1000)), null));
        assertThat(service.runInventoryReconciliation().hasDiscrepancies()).isFalse();
    }

    @Test
    void expectedProductMissingFromInventoryIsNotSilentlyIgnored() {
        when(inventory.reconciliation()).thenReturn(new ApiResponse<>(new InventorySummary(List.of(), Instant.now()), null));
        when(orders.reconciliation()).thenReturn(new ApiResponse<>(List.of(order("1", "PAYMENT_PENDING", 2)), null));
        var result = service.runInventoryReconciliation();
        assertThat(result.hasDiscrepancies()).isTrue();
        assertThat(result.discrepancies().getFirst().actual()).isEqualTo("MISSING");
    }

    @Test
    void authenticationTransportAndMalformedResponseBecomeErrorInsteadOfClean() {
        when(inventory.reconciliation()).thenThrow(new RestClientException("401 Unauthorized"));
        assertThat(service.runInventoryReconciliation().status()).isEqualTo("ERROR");
        doReturn(new ApiResponse<>(null, null)).when(inventory).reconciliation();
        assertThat(service.runInventoryReconciliation().status()).isEqualTo("ERROR");
        assertThat(service.getLastInventoryResult().error()).isNotBlank();
    }

    @Test
    void paymentReconciliationReadsEveryBoundedPage() {
        paymentPage(0, true, new OrderPayment("1", "COMPLETED", "10"));
        paymentPage(1, false, new OrderPayment("2", "COMPLETED", "20"));
        when(orders.reconciliation()).thenReturn(new ApiResponse<>(List.of(order("1", "CONFIRMED", 1),
                order("2", "SHIPPED", 1)), null));
        var result = service.runPaymentReconciliation();
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.hasDiscrepancies()).isFalse();
        verify(payments).reconciliation(0, 100);
        verify(payments).reconciliation(1, 100);
    }

    @Test
    void aLaterPaymentPageFailureInvalidatesTheWholeResult() {
        paymentPage(0, true, new OrderPayment("1", "COMPLETED", "10"));
        when(payments.reconciliation(1, 100)).thenThrow(new RestClientException("timeout"));
        assertThat(service.runPaymentReconciliation().status()).isEqualTo("ERROR");
        verifyNoInteractions(orders);
    }

    @Test
    void missingSettledPaymentAndUnrefundedCancellationAreDiscrepancies() {
        paymentPage(0, false, new OrderPayment("cancelled", "COMPLETED", "10"));
        when(orders.reconciliation()).thenReturn(new ApiResponse<>(List.of(order("missing", "DELIVERED", 1),
                order("cancelled", "CANCELLED", 1), order("pending", "PAYMENT_PENDING", 1)), null));
        var result = service.runPaymentReconciliation();
        assertThat(result.discrepancies()).hasSize(2);
        assertThat(result.hasDiscrepancies()).isTrue();
    }

    @Test
    void nonAdvancingOrDuplicatePaymentPagesCannotProduceCleanResult() {
        paymentPage(0, true);
        assertThat(service.runPaymentReconciliation().status()).isEqualTo("ERROR");
        paymentPage(0, true, new OrderPayment("1", "COMPLETED", "10"));
        paymentPage(1, false, new OrderPayment("1", "COMPLETED", "10"));
        assertThat(service.runPaymentReconciliation().status()).isEqualTo("ERROR");
    }

    @Test
    void failedErrorAlertDoesNotHideErrorStatus() {
        when(inventory.reconciliation()).thenThrow(new RestClientException("unavailable"));
        doThrow(new IllegalStateException("push unavailable")).when(push).pushReconciliationAlert(any());
        assertThat(service.runInventoryReconciliation().status()).isEqualTo("ERROR");
    }
}
