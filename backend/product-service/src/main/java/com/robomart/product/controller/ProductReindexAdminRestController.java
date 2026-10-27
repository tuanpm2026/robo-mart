package com.robomart.product.controller;

import io.micrometer.tracing.Tracer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.product.service.ProductReindexService;

/** Repeat with nextAfterId until hasNext=false; retrying a failed page is version-safe. */
@RestController
@RequestMapping("/api/v1/admin/products/reindex")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ProductReindexAdminRestController {
    private final ProductReindexService reindex;
    private final Tracer tracer;

    public ProductReindexAdminRestController(ProductReindexService reindex, Tracer tracer) {
        this.reindex = reindex;
        this.tracer = tracer;
    }

    @PostMapping
    public ApiResponse<ProductReindexService.Result> reindex(
            @RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
        var result = reindex.page(afterId, size);
        var span = tracer.currentSpan();
        return new ApiResponse<>(result, span == null ? null : span.context().traceId());
    }
}
