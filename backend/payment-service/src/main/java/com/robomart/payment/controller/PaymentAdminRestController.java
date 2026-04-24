package com.robomart.payment.controller;

import io.micrometer.tracing.Tracer;
import jakarta.validation.constraints.Max;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.dto.AuditLogDto;
import com.robomart.payment.dto.ReconciliationSummaryResponse;
import com.robomart.payment.service.AuditLogService;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.web.PaymentStatusResponse;

import java.time.Instant;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
@Validated
@RestController
@RequestMapping("/api/v1/admin/payments")
public class PaymentAdminRestController {

    private final PaymentService paymentService;
    private final AuditLogService auditLogService;
    private final Tracer tracer;

    public PaymentAdminRestController(PaymentService paymentService,
                                      AuditLogService auditLogService,
                                      Tracer tracer) {
        this.paymentService = paymentService;
        this.auditLogService = auditLogService;
        this.tracer = tracer;
    }

    @GetMapping("/reconciliation-summary")
    public ResponseEntity<ApiResponse<ReconciliationSummaryResponse>> getReconciliationSummary() {
        return ResponseEntity.ok(new ApiResponse<>(paymentService.getReconciliationSummary(), getTraceId()));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getPaymentByOrderId(
            @PathVariable String orderId) {
        var payment = paymentService.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for order: " + orderId));
        PaymentStatusResponse response = new PaymentStatusResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getTransactionId(),
                payment.getCreatedAt());
        return ResponseEntity.ok(new ApiResponse<>(response, getTraceId()));
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

    private String getTraceId() {
        io.micrometer.tracing.Span span = tracer.currentSpan();
        if (span != null) {
            io.micrometer.tracing.TraceContext ctx = span.context();
            if (ctx != null) {
                return ctx.traceId();
            }
        }
        return null;
    }
}
