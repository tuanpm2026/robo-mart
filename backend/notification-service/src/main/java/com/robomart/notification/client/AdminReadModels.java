package com.robomart.notification.client;

import java.time.Instant;
import java.util.List;

/** Read contracts owned by the downstream services; existing JSON field names stay intact. */
public final class AdminReadModels {
    private AdminReadModels() { }

    public record OrderSummary(String orderId, String status, List<OrderItem> items) { }
    public record OrderItem(Long productId, int quantity) { }
    public record InventorySummary(List<InventoryItem> items, Instant generatedAt) { }
    public record InventoryItem(Long productId, int availableQuantity, int reservedQuantity, int totalQuantity) { }
    public record PaymentSummary(List<OrderPayment> payments, Instant generatedAt, int page, int size, boolean hasNext) { }
    public record OrderPayment(String orderId, String status, String amount) { }
    public record AuditEntry(Long id, String actor, String action, String entityType, String entityId,
            String traceId, String correlationId, Instant createdAt) { }
}
