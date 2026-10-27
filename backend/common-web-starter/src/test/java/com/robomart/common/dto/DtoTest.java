package com.robomart.common.dto;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {
    @Test
    void apiErrorResponse_canBeCreatedWithErrorDetail() {
        var detail = new ErrorDetail("NOT_FOUND", "Item missing", Map.of("id", "42"));
        var now = Instant.now();
        var errorResponse = new ApiErrorResponse(detail, "trace-2", now, 400);

        assertThat(errorResponse.error()).isSameAs(detail);
        assertThat(errorResponse.error().code()).isEqualTo("NOT_FOUND");
        assertThat(errorResponse.error().message()).isEqualTo("Item missing");
        assertThat(errorResponse.error().details()).containsEntry("id", "42");
        assertThat(errorResponse.traceId()).isEqualTo("trace-2");
        assertThat(errorResponse.timestamp()).isEqualTo(now);
    }

}
