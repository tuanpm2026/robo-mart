package com.robomart.common.dto;

public record ApiResponse<T>(T data, String traceId) {
}
