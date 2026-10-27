package com.robomart.common.dto;

public record PaginationMeta(int page, int size, long totalElements, int totalPages) {
}
