package com.robomart.product.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record ReorderImagesRequest(
        @NotEmpty List<@Valid ImageOrderItem> items
) {
}
