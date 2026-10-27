package com.robomart.inventory.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BulkRestockRequest(
    @NotEmpty @Size(max = 100) List<@NotNull Long> productIds,
    @NotNull @Min(1) @Max(1000000) Integer quantity,
    String reason
) {}
