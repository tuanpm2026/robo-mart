package com.robomart.notification.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AuditAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(AuditAggregatorService.class);

    @Value("${notification.order-service.url:http://localhost:8083}")
    private String orderUrl;

    @Value("${notification.inventory-service.url:http://localhost:8084}")
    private String inventoryUrl;

    @Value("${notification.payment-service.url:http://localhost:8086}")
    private String paymentUrl;

    @Value("${notification.product-service.url:http://localhost:8081}")
    private String productUrl;

    private RestClient orderClient;
    private RestClient inventoryClient;
    private RestClient paymentClient;
    private RestClient productClient;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));

        orderClient = RestClient.builder().baseUrl(orderUrl).requestFactory(factory).build();
        inventoryClient = RestClient.builder().baseUrl(inventoryUrl).requestFactory(factory).build();
        paymentClient = RestClient.builder().baseUrl(paymentUrl).requestFactory(factory).build();
        productClient = RestClient.builder().baseUrl(productUrl).requestFactory(factory).build();
    }

    public AggregatedAuditResponse getAuditLogs(String actor, String action, String entityType,
                                                  String entityId, String traceId, String from, String to,
                                                  int page, int size) {
        List<AuditLogEntry> all = new ArrayList<>();

        all.addAll(fetchAuditLogs(orderClient, "/api/v1/admin/orders/audit-logs",
                actor, action, entityType, entityId, traceId, from, to));
        all.addAll(fetchAuditLogs(inventoryClient, "/api/v1/admin/inventory/audit-logs",
                actor, action, entityType, entityId, traceId, from, to));
        all.addAll(fetchAuditLogs(paymentClient, "/api/v1/admin/payments/audit-logs",
                actor, action, entityType, entityId, traceId, from, to));
        all.addAll(fetchAuditLogs(productClient, "/api/v1/admin/audit-logs",
                actor, action, entityType, entityId, traceId, from, to));

        all.sort(Comparator.comparing(AuditLogEntry::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        int total = all.size();
        int fromIdx = page * size;
        int toIdx = Math.min(fromIdx + size, total);
        List<AuditLogEntry> pageContent = fromIdx < total ? all.subList(fromIdx, toIdx) : List.of();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);

        return new AggregatedAuditResponse(pageContent, page, size, total, totalPages);
    }

    private List<AuditLogEntry> fetchAuditLogs(RestClient client, String path,
                                                String actor, String action, String entityType,
                                                String entityId, String traceId, String from, String to) {
        try {
            StringBuilder uri = new StringBuilder(path).append("?size=1000");
            if (actor != null) uri.append("&actor=").append(URLEncoder.encode(actor, StandardCharsets.UTF_8));
            if (action != null) uri.append("&action=").append(URLEncoder.encode(action, StandardCharsets.UTF_8));
            if (entityType != null) uri.append("&entityType=").append(URLEncoder.encode(entityType, StandardCharsets.UTF_8));
            if (entityId != null) uri.append("&entityId=").append(URLEncoder.encode(entityId, StandardCharsets.UTF_8));
            if (traceId != null) uri.append("&traceId=").append(URLEncoder.encode(traceId, StandardCharsets.UTF_8));
            if (from != null) uri.append("&from=").append(URLEncoder.encode(from, StandardCharsets.UTF_8));
            if (to != null) uri.append("&to=").append(URLEncoder.encode(to, StandardCharsets.UTF_8));

            AuditPageResponse resp = client.get()
                    .uri(uri.toString())
                    .retrieve()
                    .body(AuditPageResponse.class);

            if (resp != null && resp.data() != null) {
                return resp.data();
            }
        } catch (RestClientException e) {
            log.warn("Failed to fetch audit logs from {}: {}", path, e.getMessage());
        }
        return List.of();
    }

    public record AuditLogEntry(
            Long id,
            String actor,
            String action,
            String entityType,
            String entityId,
            String traceId,
            String correlationId,
            Instant createdAt) {}

    record AuditPageResponse(List<AuditLogEntry> data, Object pagination, String traceId) {}

    public record AggregatedAuditResponse(
            List<AuditLogEntry> data,
            int page,
            int size,
            long totalElements,
            int totalPages) {}
}
