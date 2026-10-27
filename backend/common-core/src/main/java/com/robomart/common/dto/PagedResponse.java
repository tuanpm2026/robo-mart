package com.robomart.common.dto;

import java.util.List;

public record PagedResponse<T>(List<T> data, PaginationMeta pagination, String traceId) {
}
