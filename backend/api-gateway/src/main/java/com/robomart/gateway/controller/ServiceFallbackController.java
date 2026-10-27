package com.robomart.gateway.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.tracing.Tracer;

@RestController
public class ServiceFallbackController {
    private final Tracer tracer;

    public ServiceFallbackController(Tracer tracer) {
        this.tracer = tracer;
    }

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<ProblemDetail> unavailable() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
        var span = tracer.currentSpan();
        String traceId = span != null && span.context() != null ? span.context().traceId() : "no-trace";
        problem.setProperty("code", "SERVICE_UNAVAILABLE");
        problem.setProperty("traceId", traceId);
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("error", Map.of("code", "SERVICE_UNAVAILABLE", "message", problem.getDetail()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
