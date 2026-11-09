package com.robomart.cart.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Consumes a cart snapshot once after an order has been accepted. */
public record CompleteCheckoutRequest(
        @NotNull @Positive Long orderId,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items
) {
    public record Item(
            @NotNull @Positive Long productId,
            @Positive @Max(999) int quantity,
            @NotBlank @Size(max = 100) String lineId
    ) { }
}
