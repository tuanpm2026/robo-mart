package com.robomart.product.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
