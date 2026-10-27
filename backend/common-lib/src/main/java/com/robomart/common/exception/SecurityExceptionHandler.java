package com.robomart.common.exception;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.robomart.common.dto.ApiErrorResponse;
import com.robomart.common.dto.ErrorDetail;

import io.micrometer.tracing.Tracer;

/** Keep Spring Security optional for services that delegate identity to the gateway. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
public class SecurityExceptionHandler {
    private final Tracer tracer;

    public SecurityExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        var span = tracer.currentSpan();
        String traceId = span != null && span.context() != null ? span.context().traceId() : "no-trace";
        var error = new ErrorDetail("FORBIDDEN", "Access denied", null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse(error, traceId, Instant.now(), HttpStatus.FORBIDDEN.value()));
    }
}
