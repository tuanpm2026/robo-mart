package com.robomart.common.saga;

import java.math.BigDecimal;
import java.util.List;

/** Immutable, priced input. Contains no persistence objects, shipping or card data. */
public record CheckoutSnapshot(
        String orderId,
        String sagaId,
        long epoch,
        String userId,
        String amount,
        String currency,
        int workflowVersion,
        List<Line> items) {
    public CheckoutSnapshot {
        if (orderId == null
                || !orderId.matches("[1-9][0-9]*")
                || sagaId == null
                || sagaId.isBlank()
                || epoch < 1
                || userId == null
                || userId.isBlank()
                || workflowVersion != 1
                || currency == null
                || !currency.matches("[A-Z]{3}")
                || amount == null
                || new BigDecimal(amount).signum() <= 0
                || items == null
                || items.isEmpty()) {
            throw new IllegalArgumentException("Invalid checkout snapshot");
        }
        items = List.copyOf(items);
    }

    public record Line(String productId, int quantity) {
        public Line {
            if (productId == null
                    || !productId.matches("[1-9][0-9]*")
                    || quantity < 1
                    || quantity > 999) {
                throw new IllegalArgumentException("Invalid checkout line");
            }
        }
    }
}
