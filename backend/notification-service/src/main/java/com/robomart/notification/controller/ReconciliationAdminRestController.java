package com.robomart.notification.controller;

import com.robomart.common.dto.ApiResponse;
import com.robomart.notification.service.ReconciliationService;
import com.robomart.notification.web.ReconciliationResult;
import io.micrometer.tracing.Tracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class ReconciliationAdminRestController {

    private final ReconciliationService reconciliationService;
    private final Tracer tracer;

    public ReconciliationAdminRestController(ReconciliationService reconciliationService, Tracer tracer) {
        this.reconciliationService = reconciliationService;
        this.tracer = tracer;
    }

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<List<ReconciliationResult>>> runReconciliation() {
        ReconciliationResult inventoryResult = reconciliationService.runInventoryReconciliation();
        ReconciliationResult paymentResult = reconciliationService.runPaymentReconciliation();
        return ResponseEntity.ok(new ApiResponse<>(List.of(inventoryResult, paymentResult), getTraceId()));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<List<ReconciliationResult>>> getStatus() {
        List<ReconciliationResult> results = List.of(
                reconciliationService.getLastInventoryResult(),
                reconciliationService.getLastPaymentResult());
        return ResponseEntity.ok(new ApiResponse<>(results, getTraceId()));
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
