package com.robomart.notification.controller;

import com.robomart.common.dto.ApiResponse;
import com.robomart.notification.service.AuditAggregatorService;
import io.micrometer.tracing.Tracer;
import jakarta.validation.constraints.Max;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditAdminRestController {

    private final AuditAggregatorService auditAggregatorService;
    private final Tracer tracer;

    public AuditAdminRestController(AuditAggregatorService auditAggregatorService, Tracer tracer) {
        this.auditAggregatorService = auditAggregatorService;
        this.tracer = tracer;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AuditAggregatorService.AggregatedAuditResponse>> getAuditLogs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        AuditAggregatorService.AggregatedAuditResponse result =
                auditAggregatorService.getAuditLogs(actor, action, entityType, entityId, traceId, from, to, page, size);
        return ResponseEntity.ok(new ApiResponse<>(result, getTraceId()));
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
