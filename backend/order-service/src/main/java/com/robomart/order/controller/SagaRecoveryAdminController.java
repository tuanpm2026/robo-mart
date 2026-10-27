package com.robomart.order.controller;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.order.saga.RecoveryPhase;
import com.robomart.order.saga.SagaRecoveryPolicy;

@RestController
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/orders/recovery")
public class SagaRecoveryAdminController {
    private final SagaRecoveryPolicy recovery;

    public SagaRecoveryAdminController(SagaRecoveryPolicy recovery) {
        this.recovery = recovery;
    }

    @GetMapping("/manual-reviews")
    public ApiResponse<List<SagaRecoveryPolicy.ManualReview>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return new ApiResponse<>(recovery.manualReviews(page, size), MDC.get("traceId"));
    }

    @PostMapping("/{orderId}/{phase}/resume")
    public ResponseEntity<Void> resume(@PathVariable Long orderId, @PathVariable RecoveryPhase phase) {
        return recovery.resume(orderId, phase) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
