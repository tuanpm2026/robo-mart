package com.robomart.order.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.order.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderRestController {

    private final OrderService orderService;

    public OrderRestController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) CancelOrderHttpRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String reason = (request != null) ? request.reason() : null;
        String cancelledBy = (userId != null && !userId.isBlank()) ? userId : "unknown";
        orderService.cancelOrder(orderId, reason, cancelledBy);
        return ResponseEntity.ok().build();
    }
}
