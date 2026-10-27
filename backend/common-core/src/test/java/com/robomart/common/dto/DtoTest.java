package com.robomart.common.dto;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    @Test
    void apiResponse_canBeCreatedAndFieldsAccessed() {
        var response = new ApiResponse<>("hello", "trace-1");

        assertThat(response.data()).isEqualTo("hello");
        assertThat(response.traceId()).isEqualTo("trace-1");
    }

    @Test
    void errorDetail_withNullDetails_isAllowed() {
        var detail = new ErrorDetail("ERR", "oops", null);

        assertThat(detail.code()).isEqualTo("ERR");
        assertThat(detail.details()).isNull();
    }

    @Test
    void pagedResponse_withPaginationMeta() {
        var pagination = new PaginationMeta(0, 10, 55L, 6);
        var paged = new PagedResponse<>(List.of("a", "b", "c"), pagination, "trace-3");

        assertThat(paged.data()).containsExactly("a", "b", "c");
        assertThat(paged.traceId()).isEqualTo("trace-3");
        assertThat(paged.pagination().page()).isZero();
        assertThat(paged.pagination().size()).isEqualTo(10);
        assertThat(paged.pagination().totalElements()).isEqualTo(55L);
        assertThat(paged.pagination().totalPages()).isEqualTo(6);
    }
    @Test
    void voidApiResponseRetainsItsNullableDataContract() {
        var response = new ApiResponse<Void>(null, "trace-delete");
        assertThat(response.data()).isNull();
        assertThat(response.traceId()).isEqualTo("trace-delete");
    }

}
