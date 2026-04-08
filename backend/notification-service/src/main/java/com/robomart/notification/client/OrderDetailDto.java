package com.robomart.notification.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderDetailDto(
    Long id,
    String userId,
    Instant createdAt,
    BigDecimal totalAmount,
    String status,
    String shippingAddress,
    List<OrderItemDto> items
) {}
