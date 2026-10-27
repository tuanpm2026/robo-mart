package com.robomart.common.dto;

import org.jspecify.annotations.Nullable;

public record ApiResponse<T>(@Nullable T data, String traceId) {
}
