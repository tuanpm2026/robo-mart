package com.robomart.product.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.common.exception.ValidationException;
import com.robomart.product.dto.AuditLogDto;
import com.robomart.product.dto.CategoryResponse;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductImageResponse;
import com.robomart.product.dto.ReorderImagesRequest;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.service.AuditLogService;
import com.robomart.product.service.ProductImageService;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
// GatewaySecurityConfig: .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class AdminProductRestController {

    private final ProductService productService;
    private final ProductImageService productImageService;
    private final AuditLogService auditLogService;
    private final Tracer tracer;

    public AdminProductRestController(
            ProductService productService,
            ProductImageService productImageService,
            AuditLogService auditLogService,
            Tracer tracer) {
        this.productService = productService;
        this.productImageService = productImageService;
        this.auditLogService = auditLogService;
        this.tracer = tracer;
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> createProduct(
            @RequestBody @Valid CreateProductRequest request) {
        ProductDetailResponse response = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(response, getTraceId()));
    }

    @PutMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> updateProduct(
            @PathVariable Long productId,
            @RequestBody @Valid UpdateProductRequest request) {
        ProductDetailResponse response = productService.updateProduct(productId, request);
        return ResponseEntity.ok(new ApiResponse<>(response, getTraceId()));
    }

    @DeleteMapping("/products/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId) {
        productService.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> getCategories() {
        return ResponseEntity.ok(productService.getAllCategories());
    }

    @PostMapping(value = "/products/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<ProductImageResponse>> uploadImages(
            @PathVariable Long productId,
            @RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ValidationException("No files provided");
        }
        List<ProductImageResponse> responses = productImageService.uploadImages(productId, files);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }

    @DeleteMapping("/products/{productId}/images/{imageId}")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long productId,
            @PathVariable Long imageId) {
        productImageService.deleteImage(productId, imageId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/products/{productId}/images/order")
    public ResponseEntity<List<ProductImageResponse>> reorderImages(
            @PathVariable Long productId,
            @RequestBody @Valid ReorderImagesRequest request) {
        List<ProductImageResponse> responses = productImageService.reorderImages(productId, request);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<PagedResponse<AuditLogDto>> searchAuditLogs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        Instant fromInstant = from != null ? Instant.parse(from) : null;
        Instant toInstant = to != null ? Instant.parse(to) : null;
        Page<AuditLogDto> result = auditLogService.search(actor, action, entityType, entityId, traceId,
                fromInstant, toInstant, PageRequest.of(page, size));
        return ResponseEntity.ok(new PagedResponse<>(result.getContent(),
                new PaginationMeta(result.getNumber(), result.getSize(),
                        result.getTotalElements(), result.getTotalPages()),
                getTraceId()));
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        if (span != null) {
            var context = span.context();
            if (context != null) {
                return context.traceId();
            }
        }
        return "no-trace";
    }
}
