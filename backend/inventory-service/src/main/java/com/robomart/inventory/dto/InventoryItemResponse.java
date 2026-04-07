package com.robomart.inventory.dto;

import java.time.Instant;

public record InventoryItemResponse(
    Long id,
    Long productId,
    Integer availableQuantity,
    Integer reservedQuantity,
    Integer totalQuantity,
    Integer lowStockThreshold,
    Instant updatedAt
) {}
