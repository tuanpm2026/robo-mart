package com.robomart.product.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.dto.ProductSearchRequest;
import com.robomart.product.service.ProductSearchService;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/products")
public class ProductRestController {

    private final ProductService productService;
    private final ProductSearchService productSearchService;
    private final Tracer tracer;

    public ProductRestController(ProductService productService,
                                 ProductSearchService productSearchService,
                                 Tracer tracer) {
        this.productService = productService;
        this.productSearchService = productSearchService;
        this.tracer = tracer;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ProductListResponse>> getProducts(
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        PagedResponse<ProductListResponse> response = productService.getProducts(categoryId, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    public ResponseEntity<PagedResponse<ProductListResponse>> searchProducts(
            @ModelAttribute @Valid ProductSearchRequest searchRequest,
            @PageableDefault(size = 20) Pageable pageable) {

        PagedResponse<ProductListResponse> response = productSearchService.search(searchRequest, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> getProductById(
            @PathVariable Long productId) {

        ProductDetailResponse detail = productService.getProductById(productId);
        return ResponseEntity.ok(new ApiResponse<>(detail, getTraceId()));
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
