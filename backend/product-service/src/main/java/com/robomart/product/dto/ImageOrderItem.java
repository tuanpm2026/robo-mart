package com.robomart.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ImageOrderItem(
        @NotNull Long imageId,
        @NotNull @Min(0) Integer displayOrder
) {
}
