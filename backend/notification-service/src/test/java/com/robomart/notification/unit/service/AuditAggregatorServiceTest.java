package com.robomart.notification.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.common.exception.ValidationException;
import com.robomart.notification.client.AdminReadModels.AuditEntry;
import com.robomart.notification.client.InventoryAdminReadClient;
import com.robomart.notification.client.OrderAdminReadClient;
import com.robomart.notification.client.PaymentAdminReadClient;
import com.robomart.notification.client.ProductAdminReadClient;
import com.robomart.notification.service.AuditAggregatorService;

class AuditAggregatorServiceTest {
    private final OrderAdminReadClient orders = mock(OrderAdminReadClient.class);
    private final InventoryAdminReadClient inventory = mock(InventoryAdminReadClient.class);
    private final PaymentAdminReadClient payments = mock(PaymentAdminReadClient.class);
    private final ProductAdminReadClient products = mock(ProductAdminReadClient.class);
    private final AuditAggregatorService service = new AuditAggregatorService(orders, inventory, payments, products);

    @BeforeEach
    void emptySources() {
        doAnswer(i -> page(List.of(), i.getArgument(0))).when(orders).audits(anyMap());
        doAnswer(i -> page(List.of(), i.getArgument(0))).when(inventory).audits(anyMap());
        doAnswer(i -> page(List.of(), i.getArgument(0))).when(payments).audits(anyMap());
        doAnswer(i -> page(List.of(), i.getArgument(0))).when(products).audits(anyMap());
    }

    private AuditEntry entry(long id) {
        return new AuditEntry(id, "actor", "CREATE", "Order", "order", "trace", "correlation",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id));
    }
    private PagedResponse<AuditEntry> page(List<AuditEntry> all, Map<String, String> params) {
        int page = Integer.parseInt(params.get("page"));
        int size = Integer.parseInt(params.get("size"));
        assertThat(size).isBetween(1, 100);
        List<AuditEntry> sorted = all.stream().sorted(Comparator.comparing(AuditEntry::createdAt).reversed()).toList();
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        return new PagedResponse<>(sorted.subList(from, to),
                new PaginationMeta(page, size, all.size(), (all.size() + size - 1) / size), null);
    }

    @Test
    void mergesSortedPrefixesIntoCorrectGlobalPageAndReportsAllSourceTotals() {
        doAnswer(i -> page(List.of(entry(1), entry(4)), i.getArgument(0))).when(orders).audits(anyMap());
        doAnswer(i -> page(List.of(entry(2), entry(3)), i.getArgument(0))).when(inventory).audits(anyMap());
        var result = service.getAuditLogs(null, null, null, null, null, null, null, 1, 2);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.data()).extracting(AuditEntry::id).containsExactly(2L, 1L);
        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    void fetchesMultiplePagesWithoutAnyDownstreamRequestAbove100() {
        List<AuditEntry> all = new ArrayList<>();
        for (int i = 1; i <= 180; i++) all.add(entry(i));
        doAnswer(i -> page(all, i.getArgument(0))).when(orders).audits(anyMap());
        var result = service.getAuditLogs(null, null, null, null, null, null, null, 2, 50);
        assertThat(result.data()).hasSize(50);
        assertThat(result.data().getFirst().id()).isEqualTo(80);
        assertThat(result.data().getLast().id()).isEqualTo(31);
        verify(orders, times(2)).audits(anyMap());
    }

    @Test
    void missingDownstreamReturnsExplicitErrorWithAvailableData() {
        doThrow(new RestClientException("401")).when(orders).audits(anyMap());
        doAnswer(i -> page(List.of(entry(1)), i.getArgument(0))).when(inventory).audits(anyMap());
        var result = service.getAuditLogs(null, null, null, null, null, null, null, 0, 25);
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.errors()).hasSize(1);
        assertThat(result.data()).hasSize(1);
    }

    @Test
    void forwardsFiltersWithoutManualUrlConcatenationAndFreezesTheReadCutoff() {
        service.getAuditLogs("Nguyễn A & B", "UPDATE", "Product", "42", "trace", "2026-01-01T00:00:00Z", null, 0, 25);
        verify(orders).audits(argThat(params -> "Nguyễn A & B".equals(params.get("actor"))
                && "25".equals(params.get("size")) && params.containsKey("to")));
    }

    @Test
    void malformedPaginationIsErrorAndInvalidDeepPageIsRejectedBeforeHttp() {
        doReturn(new PagedResponse<>(List.of(entry(1)), null, null)).when(orders).audits(anyMap());
        assertThat(service.getAuditLogs(null, null, null, null, null, null, null, 0, 25).status()).isEqualTo("ERROR");
        clearInvocations(orders, inventory, payments, products);
        assertThatThrownBy(() -> service.getAuditLogs(null, null, null, null, null, null, null, 1000, 100))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(orders, inventory, payments, products);
    }
}
