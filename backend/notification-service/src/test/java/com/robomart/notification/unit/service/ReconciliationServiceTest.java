package com.robomart.notification.unit.service;

import com.robomart.notification.service.AdminPushService;
import com.robomart.notification.service.ReconciliationService;
import com.robomart.notification.web.ReconciliationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String INVENTORY_URI = "/api/v1/admin/inventory/reconciliation-summary";
    private static final String PAYMENT_URI = "/api/v1/admin/payments/reconciliation-summary";
    private static final String ORDER_URI = "/api/v1/admin/orders/reconciliation-summary";

    @Mock
    private AdminPushService adminPushService;

    private ReconciliationService reconciliationService;

    // Deep-stubbed RestClients injected directly so we bypass real HTTP and drive comparison logic.
    private RestClient inventoryClient;
    private RestClient paymentClient;
    private RestClient orderClient;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(adminPushService);
        ReflectionTestUtils.setField(reconciliationService, "inventoryUrl", "http://localhost:8084");
        ReflectionTestUtils.setField(reconciliationService, "paymentUrl", "http://localhost:8086");
        ReflectionTestUtils.setField(reconciliationService, "orderUrl", "http://localhost:8083");
        ReflectionTestUtils.setField(reconciliationService, "inventoryThresholdAbsolute", 5);
        ReflectionTestUtils.setField(reconciliationService, "inventoryThresholdPercent", 1.0);
        ReflectionTestUtils.setField(reconciliationService, "paymentThresholdPercent", 1.0);

        inventoryClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        paymentClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        orderClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(reconciliationService, "inventoryClient", inventoryClient);
        ReflectionTestUtils.setField(reconciliationService, "paymentClient", paymentClient);
        ReflectionTestUtils.setField(reconciliationService, "orderClient", orderClient);
    }

    /**
     * Deserialises {@code json} into the package-private response record {@code className} (a nested
     * type of {@link ReconciliationService}) and stubs the deep RestClient chain
     * {@code client.get().uri(uri).retrieve().body(type)} to return it.
     */
    @SuppressWarnings("unchecked")
    private <T> void stubBody(RestClient client, String uri, String className, String json) {
        try {
            Class<T> type =
                    (Class<T>) Class.forName("com.robomart.notification.service.ReconciliationService$" + className);
            T body = JSON.readValue(json, type);
            when(client.get().uri(uri).retrieve().body(type)).thenReturn(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldDetectInventoryDiscrepancyAboveAbsoluteThreshold() {
        // Inventory reports reservedQuantity=20 for product 1; orders expect 10 reserved.
        // |20-10| = 10 > absolute threshold 5  => discrepancy.
        stubBody(inventoryClient, INVENTORY_URI, "InventoryReconciliationResponse",
                "{\"data\":{\"items\":[{\"productId\":1,\"availableQuantity\":80,"
                        + "\"reservedQuantity\":20,\"totalQuantity\":100}],\"generatedAt\":\"t\"},\"traceId\":\"x\"}");
        stubBody(orderClient, ORDER_URI, "OrderReconciliationListResponse",
                "{\"data\":[{\"orderId\":\"100\",\"status\":\"CONFIRMED\","
                        + "\"items\":[{\"productId\":1,\"quantity\":10}]}],\"traceId\":\"x\"}");

        ReconciliationResult result = reconciliationService.runInventoryReconciliation();

        assertThat(result.type()).isEqualTo("INVENTORY");
        assertThat(result.hasDiscrepancies()).isTrue();
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().getFirst().entityId()).isEqualTo("1");
        verify(adminPushService).pushReconciliationAlert(result);
    }

    @Test
    void shouldNotAlertWhenInventoryWithinThresholds() {
        // reserved=11 vs expected=10 => diff=1, below absolute(5) and percent(1%*max=0.1 -> 1/10=10%>1%?)
        // Use expected=1000 so percent diff is tiny: |1001-1000|=1, 1/1000=0.1% < 1% and 1 < 5.
        stubBody(inventoryClient, INVENTORY_URI, "InventoryReconciliationResponse",
                "{\"data\":{\"items\":[{\"productId\":1,\"availableQuantity\":0,"
                        + "\"reservedQuantity\":1001,\"totalQuantity\":1001}],\"generatedAt\":\"t\"},\"traceId\":\"x\"}");
        stubBody(orderClient, ORDER_URI, "OrderReconciliationListResponse",
                "{\"data\":[{\"orderId\":\"100\",\"status\":\"PAYMENT_PROCESSING\","
                        + "\"items\":[{\"productId\":1,\"quantity\":1000}]}],\"traceId\":\"x\"}");

        ReconciliationResult result = reconciliationService.runInventoryReconciliation();

        assertThat(result.hasDiscrepancies()).isFalse();
        assertThat(result.discrepancies()).isEmpty();
        verify(adminPushService, never()).pushReconciliationAlert(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyInventoryResultOnRestClientException() {
        when(inventoryClient.get().uri(eq(INVENTORY_URI)).retrieve().body(any(Class.class)))
                .thenThrow(new RestClientException("connection refused"));

        ReconciliationResult result = reconciliationService.runInventoryReconciliation();

        assertThat(result.type()).isEqualTo("INVENTORY");
        assertThat(result.discrepancies()).isEmpty();
        assertThat(reconciliationService.getLastInventoryResult()).isEqualTo(result);
        verify(adminPushService, never()).pushReconciliationAlert(any());
    }

    @Test
    void shouldDetectMissingPaymentForNonCancelledOrder() {
        // Order 200 CONFIRMED but no payment record => "MISSING" discrepancy.
        // Order 201 CANCELLED => skipped.
        stubBody(paymentClient, PAYMENT_URI, "PaymentReconciliationResponse",
                "{\"data\":{\"payments\":[],\"generatedAt\":\"t\"},\"traceId\":\"x\"}");
        stubBody(orderClient, ORDER_URI, "OrderReconciliationListResponse",
                "{\"data\":[{\"orderId\":\"200\",\"status\":\"CONFIRMED\",\"items\":[]},"
                        + "{\"orderId\":\"201\",\"status\":\"CANCELLED\",\"items\":[]}],\"traceId\":\"x\"}");

        ReconciliationResult result = reconciliationService.runPaymentReconciliation();

        assertThat(result.type()).isEqualTo("PAYMENT");
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().getFirst().entityId()).isEqualTo("200");
        assertThat(result.discrepancies().getFirst().actual()).isEqualTo("MISSING");
        verify(adminPushService).pushReconciliationAlert(result);
    }

    @Test
    void shouldDetectPaymentStatusMismatchForConfirmedOrder() {
        // Order CONFIRMED but payment status PENDING (not COMPLETED) => discrepancy.
        stubBody(paymentClient, PAYMENT_URI, "PaymentReconciliationResponse",
                "{\"data\":{\"payments\":[{\"orderId\":\"300\",\"status\":\"PENDING\",\"amount\":\"10\"}],"
                        + "\"generatedAt\":\"t\"},\"traceId\":\"x\"}");
        stubBody(orderClient, ORDER_URI, "OrderReconciliationListResponse",
                "{\"data\":[{\"orderId\":\"300\",\"status\":\"CONFIRMED\",\"items\":[]}],\"traceId\":\"x\"}");

        ReconciliationResult result = reconciliationService.runPaymentReconciliation();

        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().getFirst().expected()).isEqualTo("COMPLETED");
        assertThat(result.discrepancies().getFirst().actual()).isEqualTo("PENDING");
        verify(adminPushService).pushReconciliationAlert(result);
    }

    @Test
    void shouldNotFlagWhenPaymentCompletedForConfirmedOrder() {
        stubBody(paymentClient, PAYMENT_URI, "PaymentReconciliationResponse",
                "{\"data\":{\"payments\":[{\"orderId\":\"400\",\"status\":\"COMPLETED\",\"amount\":\"10\"}],"
                        + "\"generatedAt\":\"t\"},\"traceId\":\"x\"}");
        stubBody(orderClient, ORDER_URI, "OrderReconciliationListResponse",
                "{\"data\":[{\"orderId\":\"400\",\"status\":\"CONFIRMED\",\"items\":[]}],\"traceId\":\"x\"}");

        ReconciliationResult result = reconciliationService.runPaymentReconciliation();

        assertThat(result.hasDiscrepancies()).isFalse();
        verify(adminPushService, never()).pushReconciliationAlert(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyPaymentResultOnRestClientException() {
        when(paymentClient.get().uri(eq(PAYMENT_URI)).retrieve().body(any(Class.class)))
                .thenThrow(new RestClientException("connection refused"));

        ReconciliationResult result = reconciliationService.runPaymentReconciliation();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(reconciliationService.getLastPaymentResult()).isEqualTo(result);
        verify(adminPushService, never()).pushReconciliationAlert(any());
    }

    @Test
    void initShouldBuildClientsWithoutError() {
        // Exercises the @PostConstruct client construction path.
        ReconciliationService fresh = new ReconciliationService(adminPushService);
        ReflectionTestUtils.setField(fresh, "inventoryUrl", "http://localhost:8084");
        ReflectionTestUtils.setField(fresh, "paymentUrl", "http://localhost:8086");
        ReflectionTestUtils.setField(fresh, "orderUrl", "http://localhost:8083");
        fresh.init();
        assertThat(ReflectionTestUtils.getField(fresh, "inventoryClient")).isNotNull();
    }

    @Test
    void allComparisonMethodsArePrivate() throws Exception {
        for (Method m : ReconciliationService.class.getDeclaredMethods()) {
            if (m.getName().startsWith("compare") || m.getName().equals("isInventoryReservingStatus")) {
                assertThat(java.lang.reflect.Modifier.isPrivate(m.getModifiers())).isTrue();
            }
        }
    }
}
