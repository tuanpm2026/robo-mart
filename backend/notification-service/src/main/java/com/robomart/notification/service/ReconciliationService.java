package com.robomart.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.robomart.common.dto.ApiResponse;
import com.robomart.notification.client.AdminReadModels.InventorySummary;
import com.robomart.notification.client.AdminReadModels.OrderPayment;
import com.robomart.notification.client.AdminReadModels.OrderSummary;
import com.robomart.notification.client.InventoryAdminReadClient;
import com.robomart.notification.client.OrderAdminReadClient;
import com.robomart.notification.client.PaymentAdminReadClient;
import com.robomart.notification.web.ReconciliationDiscrepancy;
import com.robomart.notification.web.ReconciliationResult;

/** An unavailable/malformed downstream response is an ERROR, never evidence of a clean ledger. */
@Service
public class ReconciliationService {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);
    private final InventoryAdminReadClient inventory;
    private final PaymentAdminReadClient payments;
    private final OrderAdminReadClient orders;
    private final AdminPushService push;
    @Value("${notification.reconciliation.inventory-threshold-absolute:5}") private int absoluteThreshold = 5;
    @Value("${notification.reconciliation.inventory-threshold-percent:1.0}") private double percentThreshold = 1;
    private volatile ReconciliationResult lastInventory = notRun("INVENTORY");
    private volatile ReconciliationResult lastPayment = notRun("PAYMENT");

    public ReconciliationService(InventoryAdminReadClient inventory, PaymentAdminReadClient payments,
            OrderAdminReadClient orders, AdminPushService push) {
        this.inventory = inventory;
        this.payments = payments;
        this.orders = orders;
        this.push = push;
    }

    public ReconciliationResult runInventoryReconciliation() {
        try {
            InventorySummary stock = data(inventory.reconciliation());
            List<OrderSummary> orderRows = data(orders.reconciliation());
            if (stock.items() == null) throw new IllegalStateException("Inventory items are missing");
            Map<Long, Integer> expected = new HashMap<>();
            for (OrderSummary order : orderRows) {
                if (order.status() == null || order.items() == null) throw new IllegalStateException("Invalid order summary");
                if (isReserving(order.status())) {
                    for (var item : order.items()) {
                        if (item.productId() == null || item.quantity() <= 0) throw new IllegalStateException("Invalid order line");
                        expected.merge(item.productId(), item.quantity(), Math::addExact);
                    }
                }
            }
            List<ReconciliationDiscrepancy> differences = new ArrayList<>();
            Set<Long> present = new HashSet<>();
            for (var item : stock.items()) {
                if (item.productId() == null || item.reservedQuantity() < 0 || !present.add(item.productId())) {
                    throw new IllegalStateException("Invalid inventory summary");
                }
                int wanted = expected.getOrDefault(item.productId(), 0);
                long difference = Math.abs((long) item.reservedQuantity() - wanted);
                if (difference > absoluteThreshold || (double) difference / Math.max(wanted, 1) > percentThreshold / 100) {
                    differences.add(new ReconciliationDiscrepancy("InventoryItem", item.productId().toString(),
                            String.valueOf(wanted), String.valueOf(item.reservedQuantity()), "Review reservation lifecycle"));
                }
            }
            expected.forEach((product, quantity) -> {
                if (!present.contains(product)) differences.add(new ReconciliationDiscrepancy("InventoryItem",
                        product.toString(), quantity.toString(), "MISSING", "Review missing inventory record"));
            });
            lastInventory = completed("INVENTORY", differences);
        } catch (RuntimeException failed) {
            lastInventory = failed("INVENTORY", failed);
        }
        return lastInventory;
    }

    public ReconciliationResult runPaymentReconciliation() {
        try {
            Map<String, String> byOrder = new HashMap<>();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            for (int page = 0; ; page++) {
                if (page == 1000 || System.nanoTime() >= until) throw new IllegalStateException("Payment reconciliation page budget exceeded");
                var response = data(payments.reconciliation(page, 100));
                if (response.payments() == null || response.hasNext() && response.payments().isEmpty()
                        || response.size() > 0 && (response.page() != page || response.size() != 100)) {
                    throw new IllegalStateException("Invalid payment page");
                }
                for (OrderPayment payment : response.payments()) {
                    if (payment.orderId() == null || payment.status() == null
                            || byOrder.putIfAbsent(payment.orderId(), payment.status()) != null) {
                        throw new IllegalStateException("Invalid or duplicate payment summary");
                    }
                }
                if (!response.hasNext()) break;
            }
            List<ReconciliationDiscrepancy> differences = new ArrayList<>();
            for (OrderSummary order : data(orders.reconciliation())) {
                if (order.status() == null || order.orderId() == null) throw new IllegalStateException("Invalid order summary");
                String actual = byOrder.get(order.orderId());
                boolean settledOrder = Set.of("CONFIRMED", "SHIPPED", "DELIVERED").contains(order.status());
                if (settledOrder && !"COMPLETED".equals(actual)) {
                    differences.add(new ReconciliationDiscrepancy("Payment", order.orderId(), "COMPLETED",
                            actual == null ? "MISSING" : actual, "Review payment settlement"));
                } else if ("CANCELLED".equals(order.status()) && "COMPLETED".equals(actual)) {
                    differences.add(new ReconciliationDiscrepancy("Payment", order.orderId(), "REFUNDED",
                            actual, "Review cancellation refund"));
                }
            }
            lastPayment = completed("PAYMENT", differences);
        } catch (RuntimeException failed) {
            lastPayment = failed("PAYMENT", failed);
        }
        return lastPayment;
    }

    private boolean isReserving(String status) {
        // CONFIRMED and later states consume committed stock; they no longer reserve units.
        return Set.of("INVENTORY_RESERVING", "PAYMENT_PROCESSING", "PAYMENT_PENDING", "INVENTORY_RELEASING").contains(status);
    }

    private <T> T data(ApiResponse<T> response) {
        if (response == null || response.data() == null) throw new IllegalStateException("Downstream response data missing");
        return response.data();
    }

    private ReconciliationResult completed(String type, List<ReconciliationDiscrepancy> differences) {
        var result = new ReconciliationResult(type, differences, !differences.isEmpty(), Instant.now());
        if (!differences.isEmpty()) push.pushReconciliationAlert(result);
        return result;
    }

    private ReconciliationResult failed(String type, RuntimeException failure) {
        LOG.error("{} reconciliation failed", type, failure);
        var result = new ReconciliationResult(type, List.of(), false, Instant.now(), "ERROR",
                "Downstream reconciliation failed: " + failure.getClass().getSimpleName());
        try { push.pushReconciliationAlert(result); }
        catch (RuntimeException alertFailure) { LOG.error("Reconciliation error alert failed", alertFailure); }
        return result;
    }

    private static ReconciliationResult notRun(String type) {
        return new ReconciliationResult(type, List.of(), false, Instant.now(), "NOT_RUN", null);
    }

    public ReconciliationResult getLastInventoryResult() { return lastInventory; }
    public ReconciliationResult getLastPaymentResult() { return lastPayment; }
}
