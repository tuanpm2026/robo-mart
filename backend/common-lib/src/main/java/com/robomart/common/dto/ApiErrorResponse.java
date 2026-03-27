package com.robomart.common.dto;

import java.time.Instant;

public record ApiErrorResponse(ErrorDetail error, String traceId, Instant timestamp) {
}
