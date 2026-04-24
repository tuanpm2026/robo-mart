package com.robomart.order.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.order.dto.AuditLogDto;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.service.AuditLogService;
import com.robomart.order.service.OrderService;
import com.robomart.order.web.AdminOrderDetailResponse;
import com.robomart.order.web.AdminOrderSummaryResponse;
import com.robomart.order.web.OrderDashboardMetricsResponse;
import com.robomart.order.web.OrderEventResponse;
import com.robomart.order.web.OrderReconciliationSummary;
import com.robomart.order.web.UpdateOrderStatusRequest;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
// GatewaySecurityConfig: .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
@Validated
@RestController
@RequestMapping("/api/v1/admin/orders")
public class OrderAdminRestController {

    private final OrderService orderService;
    private final AuditLogService auditLogService;
    private final Tracer tracer;

    public OrderAdminRestController(OrderService orderService,
                                    AuditLogService auditLogService,
                                    Tracer tracer) {
        this.orderService = orderService;
        this.auditLogService = auditLogService;
        this.tracer = tracer;
    }

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<OrderDashboardMetricsResponse>> getDashboardMetrics() {
        OrderDashboardMetricsResponse metrics = orderService.getDashboardMetrics();
        return ResponseEntity.ok(new ApiResponse<>(metrics, getTraceId()));
    }

    @GetMapping("/reconciliation-summary")
    public ResponseEntity<ApiResponse<List<OrderReconciliationSummary>>> getReconciliationSummary() {
        return ResponseEntity.ok(new ApiResponse<>(orderService.getOrderReconciliationSummary(), getTraceId()));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<AdminOrderSummaryResponse>> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") @Max(100) int size,
            @RequestParam(required = false) List<OrderStatus> statuses) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Page<AdminOrderSummaryResponse> orders = orderService.getAllOrders(safePage, safeSize, statuses);
        PagedResponse<AdminOrderSummaryResponse> response = new PagedResponse<>(
                orders.getContent(),
                new PaginationMeta(orders.getNumber(), orders.getSize(),
                        orders.getTotalElements(), orders.getTotalPages()),
                getTraceId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<AdminOrderDetailResponse>> getOrderDetail(
            @PathVariable Long orderId) {
        AdminOrderDetailResponse detail = orderService.getOrderDetailForAdmin(orderId);
        return ResponseEntity.ok(new ApiResponse<>(detail, getTraceId()));
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<ApiResponse<AdminOrderSummaryResponse>> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody @Valid UpdateOrderStatusRequest request) {
        Order order = orderService.updateOrderStatus(orderId, request.status());
        int itemCount = orderService.getOrderItemCount(orderId);
        AdminOrderSummaryResponse summary = new AdminOrderSummaryResponse(
                order.getId(),
                order.getUserId(),
                order.getCreatedAt(),
                order.getTotalAmount(),
                order.getStatus(),
                itemCount,
                order.getCancellationReason());
        return ResponseEntity.ok(new ApiResponse<>(summary, getTraceId()));
    }

    @GetMapping("/{orderId}/events")
    public ResponseEntity<ApiResponse<List<OrderEventResponse>>> getOrderEvents(@PathVariable Long orderId) {
        List<OrderStatusHistory> history = orderService.getOrderStatusHistory(orderId);
        if (history.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<OrderEventResponse> events = history.stream()
                .map(h -> new OrderEventResponse(h.getId(), h.getStatus().name(), h.getChangedAt()))
                .toList();
        return ResponseEntity.ok(new ApiResponse<>(events, getTraceId()));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<PagedResponse<AuditLogDto>> searchAuditLogs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        Instant fromInstant = from != null ? Instant.parse(from) : null;
        Instant toInstant = to != null ? Instant.parse(to) : null;
        Page<AuditLogDto> result = auditLogService.search(actor, action, entityType, entityId, traceId,
                fromInstant, toInstant, PageRequest.of(page, size));
        return ResponseEntity.ok(new PagedResponse<>(result.getContent(),
                new PaginationMeta(result.getNumber(), result.getSize(),
                        result.getTotalElements(), result.getTotalPages()),
                getTraceId()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "CONFLICT",
                        "message", "Order was modified concurrently. Please retry.",
                        "traceId", getTraceId() != null ? getTraceId() : "no-trace"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "INVALID_TRANSITION",
                        "message", ex.getMessage(),
                        "traceId", getTraceId() != null ? getTraceId() : "no-trace"));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "BAD_REQUEST",
                        "message", "Invalid request body: " + ex.getMostSpecificCause().getMessage(),
                        "traceId", getTraceId() != null ? getTraceId() : "no-trace"));
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
