package com.robomart.common.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.robomart.common.dto.ApiErrorResponse;
import com.robomart.common.dto.ErrorDetail;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;

    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessRule(BusinessRuleException ex) {
        log.warn("Business rule violation: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ValidationException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiErrorResponse> handleExternalService(ExternalServiceException ex) {
        log.error("External service error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fieldErrors.put(e.getField(), e.getDefaultMessage()));
        var error = new ErrorDetail("VALIDATION_ERROR", "Request validation failed", fieldErrors);
        var response = new ApiErrorResponse(error, getTraceId(), Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        var error = new ErrorDetail("METHOD_NOT_ALLOWED", ex.getMessage(), null);
        var response = new ApiErrorResponse(error, getTraceId(), Instant.now());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        var error = new ErrorDetail("INTERNAL_ERROR", "An unexpected error occurred", null);
        var response = new ApiErrorResponse(error, getTraceId(), Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(HttpStatus status, RoboMartException ex) {
        var error = new ErrorDetail(ex.getErrorCode().name(), ex.getMessage(), null);
        var response = new ApiErrorResponse(error, getTraceId(), Instant.now());
        return ResponseEntity.status(status).body(response);
    }

    private String getTraceId() {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            TraceContext context = currentSpan.context();
            if (context != null) {
                return context.traceId();
            }
        }
        return "no-trace";
    }
}
