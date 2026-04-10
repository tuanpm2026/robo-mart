package com.robomart.order.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.tracing.Tracer;

import com.robomart.common.dto.ApiResponse;
import com.robomart.order.service.ReportService;
import com.robomart.order.web.ReportSummaryResponse;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
@RestController
@RequestMapping("/api/v1/admin/reports")
public class ReportAdminRestController {

    private final ReportService reportService;
    private final Tracer tracer;

    public ReportAdminRestController(ReportService reportService, Tracer tracer) {
        this.reportService = reportService;
        this.tracer = tracer;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<ReportSummaryResponse>> getSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from != null
                    ? Instant.parse(from)
                    : LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            toInstant = to != null ? Instant.parse(to) : Instant.now();
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(null, getTraceId()));
        }
        if (fromInstant.isAfter(toInstant)) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(null, getTraceId()));
        }
        ReportSummaryResponse summary = reportService.getSummary(fromInstant, toInstant);
        return ResponseEntity.ok(new ApiResponse<>(summary, getTraceId()));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<ApiResponse<String>> rebuild() {
        String timestamp = reportService.rebuildReadModels();
        return ResponseEntity.ok(new ApiResponse<>("Rebuild initiated at " + timestamp, getTraceId()));
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
