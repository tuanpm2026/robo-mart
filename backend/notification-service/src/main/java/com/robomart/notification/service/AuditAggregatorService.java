package com.robomart.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.robomart.common.dto.PagedResponse;
import com.robomart.common.exception.ValidationException;
import com.robomart.notification.client.AdminReadModels.AuditEntry;
import com.robomart.notification.client.InventoryAdminReadClient;
import com.robomart.notification.client.OrderAdminReadClient;
import com.robomart.notification.client.PaymentAdminReadClient;
import com.robomart.notification.client.ProductAdminReadClient;

/** Fetch only the sorted prefixes needed for a global page; failures stay explicit. */
@Service
public class AuditAggregatorService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditAggregatorService.class);
    private static final int MAX_WINDOW = 10000;
    private final List<Source> sources;

    public AuditAggregatorService(OrderAdminReadClient orders, InventoryAdminReadClient inventory,
            PaymentAdminReadClient payments, ProductAdminReadClient products) {
        sources = List.of(new Source("order", orders::audits), new Source("inventory", inventory::audits),
                new Source("payment", payments::audits), new Source("product", products::audits));
    }

    public AggregatedAuditResponse getAuditLogs(String actor, String action, String entityType,
            String entityId, String traceId, String from, String to, int page, int size) {
        long wanted = ((long) page + 1) * size;
        if (page < 0 || size < 1 || size > 100 || wanted > MAX_WINDOW) {
            throw new ValidationException("Audit page must be within 10,000 results; narrow the filters for deeper history");
        }
        Map<String, String> filters = new HashMap<>();
        put(filters, "actor", actor); put(filters, "action", action); put(filters, "entityType", entityType);
        put(filters, "entityId", entityId); put(filters, "traceId", traceId); put(filters, "from", from);
        filters.put("to", to == null ? Instant.now().toString() : to);
        List<AuditEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long total = 0;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Source source : sources) {
            try {
                SourcePage result = fetch(source, filters, (int) wanted, until);
                entries.addAll(result.entries());
                total = Math.addExact(total, result.total());
            } catch (RuntimeException failed) {
                LOG.error("Audit read failed: service={}", source.name(), failed);
                errors.add(source.name() + ": " + failed.getClass().getSimpleName());
            }
        }
        entries.sort(Comparator.comparing(AuditEntry::createdAt, Comparator.reverseOrder())
                .thenComparing(AuditEntry::id, Comparator.nullsLast(Comparator.reverseOrder())));
        int start = Math.min(page * size, entries.size());
        int end = Math.min(start + size, entries.size());
        int pages = (int) Math.min(Integer.MAX_VALUE, Math.ceil((double) total / size));
        return new AggregatedAuditResponse(List.copyOf(entries.subList(start, end)), page, size, total, pages,
                errors.isEmpty() ? "OK" : "ERROR", List.copyOf(errors));
    }

    private SourcePage fetch(Source source, Map<String, String> filters, int wanted, long until) {
        int size = Math.min(100, wanted);
        List<AuditEntry> entries = new ArrayList<>();
        long total = 0;
        for (int page = 0; entries.size() < wanted; page++) {
            if (System.nanoTime() >= until) throw new IllegalStateException("Audit request budget exceeded");
            Map<String, String> params = new HashMap<>(filters);
            params.put("page", String.valueOf(page));
            params.put("size", String.valueOf(size));
            PagedResponse<AuditEntry> response = source.read().apply(params);
            if (response == null || response.data() == null || response.pagination() == null
                    || response.pagination().page() != page || response.pagination().size() != size
                    || response.data().size() > size || response.pagination().totalElements() < 0) throw new IllegalStateException("Invalid audit page");
            if (response.data().stream().anyMatch(entry -> entry == null || entry.createdAt() == null)) {
                throw new IllegalStateException("Invalid audit entry");
            }
            if (page == 0) total = response.pagination().totalElements();
            entries.addAll(response.data());
            if (page + 1 >= response.pagination().totalPages()) break;
            if (response.data().isEmpty()) throw new IllegalStateException("Audit page did not advance");
        }
        return new SourcePage(entries, total);
    }

    private void put(Map<String, String> values, String name, String value) {
        if (value != null) values.put(name, value);
    }

    private record Source(String name, Function<Map<String, String>, PagedResponse<AuditEntry>> read) { }
    private record SourcePage(List<AuditEntry> entries, long total) { }

    public record AggregatedAuditResponse(List<AuditEntry> data, int page, int size, long totalElements,
            int totalPages, String status, List<String> errors) {
        public AggregatedAuditResponse(List<AuditEntry> data, int page, int size, long totalElements, int totalPages) {
            this(data, page, size, totalElements, totalPages, "OK", List.of());
        }
    }
}
