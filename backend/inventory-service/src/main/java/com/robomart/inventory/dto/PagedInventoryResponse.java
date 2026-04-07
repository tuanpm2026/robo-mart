package com.robomart.inventory.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedInventoryResponse(
    List<InventoryItemResponse> data,
    PaginationMeta pagination,
    String traceId
) {
    public record PaginationMeta(int page, int size, long totalElements, int totalPages) {}

    public PagedInventoryResponse(List<InventoryItemResponse> data, Page<?> page, String traceId) {
        this(data,
            new PaginationMeta(page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages()),
            traceId);
    }
}
