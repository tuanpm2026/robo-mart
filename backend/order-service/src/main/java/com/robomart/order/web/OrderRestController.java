package com.robomart.order.web;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderInventoryFailedException;
import com.robomart.order.exception.OrderPaymentFailedException;
import com.robomart.order.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderRestController {

    private final OrderService orderService;

    public OrderRestController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderSummaryResponse>> createOrder(
            @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResourceNotFoundException("Unauthorized");
        }
        List<OrderService.OrderItemRequest> items = request.items().stream()
                .map(item -> new OrderService.OrderItemRequest(
                        item.productId(), item.productName(), item.quantity(), item.unitPrice()))
                .toList();
        Order order = orderService.createOrder(userId, items, request.shippingAddress());
        if (order.getStatus() == OrderStatus.CANCELLED) {
            String reason = order.getCancellationReason() != null ? order.getCancellationReason() : "Order processing failed";
            String reasonLower = reason.toLowerCase();
            if (reasonLower.contains("stock") || reasonLower.contains("inventory") || reasonLower.contains("insufficient")) {
                throw new OrderInventoryFailedException(reason);
            }
            throw new OrderPaymentFailedException(reason);
        }
        OrderSummaryResponse summary = new OrderSummaryResponse(
                order.getId(),
                order.getCreatedAt(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getItems().size(),
                order.getCancellationReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(summary, MDC.get("traceId")));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<OrderSummaryResponse>> listOrders(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        if (userId == null || userId.isBlank()) {
            Page<OrderSummaryResponse> empty = Page.empty();
            PagedResponse<OrderSummaryResponse> response = new PagedResponse<>(
                    empty.getContent(),
                    new PaginationMeta(safePage, safeSize, 0, 0),
                    MDC.get("traceId"));
            return ResponseEntity.ok(response);
        }
        Page<OrderSummaryResponse> orders = orderService.getOrdersByUser(userId, safePage, safeSize);
        PagedResponse<OrderSummaryResponse> response = new PagedResponse<>(
                orders.getContent(),
                new PaginationMeta(orders.getNumber(), orders.getSize(), orders.getTotalElements(), orders.getTotalPages()),
                MDC.get("traceId"));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderDetailResponse>> getOrder(
            @PathVariable Long orderId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
        OrderDetailResponse detail = orderService.getOrderForUser(orderId, userId);
        return ResponseEntity.ok(new ApiResponse<>(detail, MDC.get("traceId")));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) CancelOrderHttpRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
        String reason = (request != null) ? request.reason() : null;
        orderService.cancelOrder(orderId, reason, userId);
        return ResponseEntity.ok().build();
    }
}
