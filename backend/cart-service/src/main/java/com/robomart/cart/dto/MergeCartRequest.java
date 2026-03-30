package com.robomart.cart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MergeCartRequest(
        @NotBlank(message = "Anonymous cart ID is required")
        @Size(max = 128, message = "Anonymous cart ID must not exceed 128 characters")
        String anonymousCartId
) {
}
