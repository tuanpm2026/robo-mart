package com.robomart.notification.service;

import com.robomart.notification.web.ReconciliationDiscrepancy;
import com.robomart.notification.web.ReconciliationResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    @Value("${notification.inventory-service.url:http://localhost:8084}")
    private String inventoryUrl;

    @Value("${notification.payment-service.url:http://localhost:8086}")
    private String paymentUrl;

    @Value("${notification.order-service.url:http://localhost:8083}")
    private String orderUrl;

    @Value("${notification.reconciliation.inventory-threshold-absolute:5}")
    private int inventoryThresholdAbsolute;

    @Value("${notification.reconciliation.inventory-threshold-percent:1.0}")
    private double inventoryThresholdPercent;

    @Value("${notification.reconciliation.payment-threshold-percent:1.0}")
    private double paymentThresholdPercent;

    private RestClient inventoryClient;
    private RestClient paymentClient;
    private RestClient orderClient;

    private final AdminPushService adminPushService;

    private volatile ReconciliationResult lastInventoryResult =
            new ReconciliationResult("INVENTORY", List.of(), false, Instant.now());
    private volatile ReconciliationResult lastPaymentResult =
            new ReconciliationResult("PAYMENT", List.of(), false, Instant.now());

    public ReconciliationService(AdminPushService adminPushService) {
        this.adminPushService = adminPushService;
    }

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));

        inventoryClient = RestClient.builder().baseUrl(inventoryUrl).requestFactory(factory).build();
        paymentClient = RestClient.builder().baseUrl(paymentUrl).requestFactory(factory).build();
        orderClient = RestClient.builder().baseUrl(orderUrl).requestFactory(factory).build();
    }

    public ReconciliationResult runInventoryReconciliation() {
        try {
            InventoryReconciliationResponse inventoryResp = inventoryClient.get()
                    .uri("/api/v1/admin/inventory/reconciliation-summary")
                    .retrieve()
                    .body(InventoryReconciliationResponse.class);

            OrderReconciliationListResponse orderResp = orderClient.get()
                    .uri("/api/v1/admin/orders/reconciliation-summary")
                    .retrieve()
                    .body(OrderReconciliationListResponse.class);

            List<ReconciliationDiscrepancy> discrepancies = compareInventory(inventoryResp, orderResp);
            ReconciliationResult result = new ReconciliationResult(
                    "INVENTORY",
                    discrepancies,
                    !discrepancies.isEmpty(),
                    Instant.now());

            lastInventoryResult = result;

            if (!discrepancies.isEmpty()) {
                log.warn("Inventory reconciliation found {} discrepancies", discrepancies.size());
                adminPushService.pushReconciliationAlert(result);
            }

            return result;
        } catch (RestClientException e) {
            log.warn("Inventory reconciliation failed: {}", e.getMessage());
            ReconciliationResult empty = new ReconciliationResult("INVENTORY", List.of(), false, Instant.now());
            lastInventoryResult = empty;
            return empty;
        }
    }

    public ReconciliationResult runPaymentReconciliation() {
        try {
            PaymentReconciliationResponse paymentResp = paymentClient.get()
                    .uri("/api/v1/admin/payments/reconciliation-summary")
                    .retrieve()
                    .body(PaymentReconciliationResponse.class);

            OrderReconciliationListResponse orderResp = orderClient.get()
                    .uri("/api/v1/admin/orders/reconciliation-summary")
                    .retrieve()
                    .body(OrderReconciliationListResponse.class);

            List<ReconciliationDiscrepancy> discrepancies = comparePayments(paymentResp, orderResp);
            ReconciliationResult result = new ReconciliationResult(
                    "PAYMENT",
                    discrepancies,
                    !discrepancies.isEmpty(),
                    Instant.now());

            lastPaymentResult = result;

            if (!discrepancies.isEmpty()) {
                log.warn("Payment reconciliation found {} discrepancies", discrepancies.size());
                adminPushService.pushReconciliationAlert(result);
            }

            return result;
        } catch (RestClientException e) {
            log.warn("Payment reconciliation failed: {}", e.getMessage());
            ReconciliationResult empty = new ReconciliationResult("PAYMENT", List.of(), false, Instant.now());
            lastPaymentResult = empty;
            return empty;
        }
    }

    public ReconciliationResult getLastInventoryResult() {
        return lastInventoryResult;
    }

    public ReconciliationResult getLastPaymentResult() {
        return lastPaymentResult;
    }

    private List<ReconciliationDiscrepancy> compareInventory(
            InventoryReconciliationResponse inventoryResp,
            OrderReconciliationListResponse orderResp) {

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();

        if (inventoryResp == null || inventoryResp.data() == null) {
            return discrepancies;
        }

        // Compute expected reserved quantity from orders in active saga states
        Map<Long, Integer> expectedReserved = new HashMap<>();
        if (orderResp != null && orderResp.data() != null) {
            for (OrderReconciliationSummaryDto order : orderResp.data()) {
                if (isInventoryReservingStatus(order.status()) && order.items() != null) {
                    for (OrderItemSummaryDto item : order.items()) {
                        expectedReserved.merge(item.productId(), item.quantity(), Integer::sum);
                    }
                }
            }
        }

        for (ProductInventorySummaryDto inv : inventoryResp.data().items()) {
            int actual = inv.reservedQuantity();
            int expected = expectedReserved.getOrDefault(inv.productId(), 0);
            int diff = Math.abs(actual - expected);
            int maxVal = Math.max(expected, 1);
            boolean absoluteExceeded = diff > inventoryThresholdAbsolute;
            boolean percentExceeded = (double) diff / maxVal > inventoryThresholdPercent / 100.0;
            if (absoluteExceeded || percentExceeded) {
                discrepancies.add(new ReconciliationDiscrepancy(
                        "InventoryItem",
                        String.valueOf(inv.productId()),
                        String.valueOf(expected),
                        String.valueOf(actual),
                        "Check order saga state for productId=" + inv.productId()));
            }
        }

        return discrepancies;
    }

    private boolean isInventoryReservingStatus(String status) {
        return "INVENTORY_RESERVING".equals(status)
                || "PAYMENT_PROCESSING".equals(status)
                || "CONFIRMED".equals(status)
                || "PAYMENT_PENDING".equals(status);
    }

    private List<ReconciliationDiscrepancy> comparePayments(
            PaymentReconciliationResponse paymentResp,
            OrderReconciliationListResponse orderResp) {

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();

        if (orderResp == null || orderResp.data() == null) {
            return discrepancies;
        }

        Map<String, String> paymentsByOrderId = new HashMap<>();
        if (paymentResp != null && paymentResp.data() != null) {
            for (OrderPaymentSummaryDto p : paymentResp.data().payments()) {
                paymentsByOrderId.put(p.orderId(), p.status());
            }
        }

        for (OrderReconciliationSummaryDto order : orderResp.data()) {
            if ("CANCELLED".equals(order.status())) {
                continue;
            }
            String paymentStatus = paymentsByOrderId.get(order.orderId());
            if (paymentStatus == null) {
                discrepancies.add(new ReconciliationDiscrepancy(
                        "Payment",
                        order.orderId(),
                        "EXISTS",
                        "MISSING",
                        "Verify payment processing for orderId=" + order.orderId()));
            } else if ("CONFIRMED".equals(order.status()) && !"COMPLETED".equals(paymentStatus)) {
                discrepancies.add(new ReconciliationDiscrepancy(
                        "Payment",
                        order.orderId(),
                        "COMPLETED",
                        paymentStatus,
                        "Verify payment processing for orderId=" + order.orderId()));
            }
        }

        return discrepancies;
    }

    // --- Internal DTOs for deserializing responses from other services ---

    record InventoryReconciliationResponse(ProductInventorySummaryListDto data, String traceId) {}

    record ProductInventorySummaryListDto(List<ProductInventorySummaryDto> items, String generatedAt) {}

    record ProductInventorySummaryDto(Long productId, int availableQuantity, int reservedQuantity, int totalQuantity) {}

    record OrderReconciliationListResponse(List<OrderReconciliationSummaryDto> data, String traceId) {}

    record OrderReconciliationSummaryDto(String orderId, String status, List<OrderItemSummaryDto> items) {}

    record OrderItemSummaryDto(Long productId, int quantity) {}

    record PaymentReconciliationResponse(PaymentSummaryListDto data, String traceId) {}

    record PaymentSummaryListDto(List<OrderPaymentSummaryDto> payments, String generatedAt) {}

    record OrderPaymentSummaryDto(String orderId, String status, String amount) {}
}
