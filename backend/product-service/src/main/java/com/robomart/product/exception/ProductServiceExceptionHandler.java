package com.robomart.product.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.robomart.common.dto.ApiErrorResponse;
import com.robomart.common.dto.ErrorDetail;
import io.micrometer.tracing.Tracer;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductServiceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceExceptionHandler.class);
    private final Tracer tracer;

    public ProductServiceExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticConflict(ObjectOptimisticLockingFailureException ex) {
        return response(HttpStatus.CONFLICT, "PRODUCT_CONFLICT", "Product changed; reload before updating");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && "products_sku_key".equals(constraint.getConstraintName())) {
                return response(HttpStatus.CONFLICT, "PRODUCT_CONFLICT", "Product SKU already exists");
            }
        }
        log.error("Product integrity failure", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Product persistence failed");
    }

    @ExceptionHandler(ImageStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleImageStorage(ImageStorageException ex) {
        log.error("Image storage error: {}", ex.getMessage(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_STORAGE_ERROR", "Image storage operation failed");
    }

    /**
     * Maps an oversized multipart upload to 413 Payload Too Large instead of letting it surface as a
     * generic 500. Spring raises {@link MaxUploadSizeExceededException} when the request body exceeds
     * the configured {@code spring.servlet.multipart.max-file-size}/{@code max-request-size}.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("Rejected oversized upload: {}", ex.getMessage());
        return response(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Uploaded file exceeds the maximum allowed size");
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        var span = tracer.currentSpan();
        String traceId = span != null && span.context() != null ? span.context().traceId() : "no-trace";
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ErrorDetail(code, message, null), traceId, Instant.now(), status.value()));
    }
}
