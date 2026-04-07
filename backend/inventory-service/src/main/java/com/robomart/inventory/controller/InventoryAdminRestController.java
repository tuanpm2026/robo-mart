package com.robomart.inventory.controller;

import com.robomart.common.dto.ApiResponse;
import com.robomart.inventory.dto.BulkRestockRequest;
import com.robomart.inventory.dto.InventoryItemResponse;
import com.robomart.inventory.dto.PagedInventoryResponse;
import com.robomart.inventory.dto.RestockRequest;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.service.InventoryService;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
// GatewaySecurityConfig: .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
@Validated
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class InventoryAdminRestController {

    private final InventoryService inventoryService;
    private final Tracer tracer;

    public InventoryAdminRestController(InventoryService inventoryService, Tracer tracer) {
        this.inventoryService = inventoryService;
        this.tracer = tracer;
    }

    @GetMapping
    public ResponseEntity<PagedInventoryResponse> listInventory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") @Max(100) int size) {
        Page<InventoryItem> result = inventoryService.listInventory(
                PageRequest.of(page, size, Sort.by("productId").ascending()));
        List<InventoryItemResponse> responses = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(new PagedInventoryResponse(responses, result, getTraceId()));
    }

    @PutMapping("/{productId}/restock")
    public ResponseEntity<ApiResponse<InventoryItemResponse>> restockItem(
            @PathVariable Long productId,
            @RequestBody @Valid RestockRequest request) {
        InventoryItem item = inventoryService.restockItem(
                productId, request.quantity(), request.reason());
        return ResponseEntity.ok(new ApiResponse<>(toResponse(item), getTraceId()));
    }

    @PostMapping("/bulk-restock")
    public ResponseEntity<List<InventoryItemResponse>> bulkRestock(
            @RequestBody @Valid BulkRestockRequest request) {
        List<InventoryItem> items = inventoryService.bulkRestock(
                request.productIds(), request.quantity(), request.reason());
        return ResponseEntity.ok(items.stream().map(this::toResponse).toList());
    }

    private InventoryItemResponse toResponse(InventoryItem item) {
        return new InventoryItemResponse(
                item.getId(),
                item.getProductId(),
                item.getAvailableQuantity(),
                item.getReservedQuantity(),
                item.getTotalQuantity(),
                item.getLowStockThreshold(),
                item.getUpdatedAt());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "CONFLICT",
                        "message", "Inventory was modified concurrently. Please retry.",
                        "traceId", getTraceId() != null ? getTraceId() : "no-trace"
                ));
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
