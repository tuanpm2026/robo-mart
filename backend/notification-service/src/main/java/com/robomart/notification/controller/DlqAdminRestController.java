package com.robomart.notification.controller;

import java.util.NoSuchElementException;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.tracing.Tracer;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.service.FailedEventService;
import com.robomart.notification.web.DlqEventResponse;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
@RestController
@RequestMapping("/api/v1/admin/dlq")
public class DlqAdminRestController {

    private static final int MAX_PAGE_SIZE = 200;

    private final FailedEventService failedEventService;
    private final Tracer tracer;

    public DlqAdminRestController(FailedEventService failedEventService, Tracer tracer) {
        this.failedEventService = failedEventService;
        this.tracer = tracer;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<DlqEventResponse>> listEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }
        int cappedSize = Math.min(size, MAX_PAGE_SIZE);
        Page<FailedEvent> events = failedEventService.listEvents(page, cappedSize);
        Page<DlqEventResponse> mapped = events.map(this::toResponse);
        PagedResponse<DlqEventResponse> response = new PagedResponse<>(
                mapped.getContent(),
                new PaginationMeta(mapped.getNumber(), mapped.getSize(),
                        mapped.getTotalElements(), mapped.getTotalPages()),
                getTraceId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<String>> retrySingle(@PathVariable Long id) {
        try {
            failedEventService.retryEvent(id);
            return ResponseEntity.ok(new ApiResponse<>("Event processed", getTraceId()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404)
                    .body(new ApiResponse<>("Event not found: " + id, getTraceId()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400)
                    .body(new ApiResponse<>(e.getMessage(), getTraceId()));
        }
    }

    @PostMapping("/retry-all")
    public ResponseEntity<ApiResponse<String>> retryAll() {
        int count = failedEventService.retryAll();
        return ResponseEntity.ok(new ApiResponse<>(count + " events processed", getTraceId()));
    }

    private DlqEventResponse toResponse(FailedEvent event) {
        return new DlqEventResponse(
                event.getId(),
                event.getEventType(),
                event.getAggregateId(),
                event.getOriginalTopic(),
                event.getErrorClass(),
                event.getErrorMessage(),
                event.getPayloadPreview(),
                event.getRetryCount(),
                event.getStatus(),
                event.getFirstFailedAt(),
                event.getLastAttemptedAt());
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
