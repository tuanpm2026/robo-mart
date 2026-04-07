package com.robomart.product.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.product.dto.CategoryResponse;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
// GatewaySecurityConfig: .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminProductRestController {

    private final ProductService productService;
    private final Tracer tracer;

    public AdminProductRestController(ProductService productService, Tracer tracer) {
        this.productService = productService;
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
