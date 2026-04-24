package com.robomart.inventory.dto;

import java.time.Instant;
import java.util.List;

public record ReconciliationSummaryResponse(List<ProductInventorySummary> items, Instant generatedAt) {
    public record ProductInventorySummary(Long productId, int availableQuantity, int reservedQuantity, int totalQuantity) {}
}
