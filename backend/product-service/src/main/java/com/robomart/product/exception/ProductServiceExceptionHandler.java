package com.robomart.product.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.robomart.common.dto.ApiErrorResponse;
import com.robomart.common.dto.ErrorDetail;

@RestControllerAdvice
public class ProductServiceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceExceptionHandler.class);

    @ExceptionHandler(ImageStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleImageStorage(ImageStorageException ex) {
        log.error("Image storage error: {}", ex.getMessage(), ex);
        var error = new ErrorDetail("IMAGE_STORAGE_ERROR", "Image storage operation failed", null);
        var response = new ApiErrorResponse(error, "no-trace", Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Maps an oversized multipart upload to 413 Payload Too Large instead of letting it surface as a
     * generic 500. Spring raises {@link MaxUploadSizeExceededException} when the request body exceeds
     * the configured {@code spring.servlet.multipart.max-file-size}/{@code max-request-size}.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("Rejected oversized upload: {}", ex.getMessage());
        var error = new ErrorDetail("FILE_TOO_LARGE", "Uploaded file exceeds the maximum allowed size", null);
        var response = new ApiErrorResponse(error, "no-trace", Instant.now());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(response);
    }
}
