package com.robomart.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BulkRestockRequest(
    @NotEmpty List<@NotNull Long> productIds,
    @NotNull @Min(1) Integer quantity,
    String reason
) {}
