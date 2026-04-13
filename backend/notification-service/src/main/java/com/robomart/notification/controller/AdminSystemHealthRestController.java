package com.robomart.notification.controller;

import io.micrometer.tracing.Tracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.notification.service.HealthAggregatorService;
import com.robomart.notification.web.SystemHealthResponse;

// No @PreAuthorize — ADMIN enforced at API Gateway level
@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemHealthRestController {

    private final HealthAggregatorService healthAggregatorService;
    private final Tracer tracer;

    public AdminSystemHealthRestController(HealthAggregatorService healthAggregatorService, Tracer tracer) {
        this.healthAggregatorService = healthAggregatorService;
        this.tracer = tracer;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<SystemHealthResponse>> getSystemHealth() {
        SystemHealthResponse health = healthAggregatorService.getLatestHealth();
        if (health == null) {
            health = healthAggregatorService.aggregateHealth();
        }
        String traceId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : null;
        return ResponseEntity.ok(new ApiResponse<>(health, traceId));
    }
}
