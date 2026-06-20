package com.robomart.notification.unit.service;

import com.robomart.notification.service.AuditAggregatorService;
import com.robomart.notification.service.AuditAggregatorService.AggregatedAuditResponse;
import com.robomart.notification.service.AuditAggregatorService.AuditLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditAggregatorServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AuditAggregatorService service;
    private RestClient orderClient;
    private RestClient inventoryClient;
    private RestClient paymentClient;
    private RestClient productClient;

    @BeforeEach
    void setUp() {
        service = new AuditAggregatorService();
        ReflectionTestUtils.setField(service, "orderUrl", "http://localhost:8083");
        ReflectionTestUtils.setField(service, "inventoryUrl", "http://localhost:8084");
        ReflectionTestUtils.setField(service, "paymentUrl", "http://localhost:8086");
        ReflectionTestUtils.setField(service, "productUrl", "http://localhost:8081");

        orderClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        inventoryClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        paymentClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        productClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(service, "orderClient", orderClient);
        ReflectionTestUtils.setField(service, "inventoryClient", inventoryClient);
        ReflectionTestUtils.setField(service, "paymentClient", paymentClient);
        ReflectionTestUtils.setField(service, "productClient", productClient);
    }

    /** Stubs the deep RestClient chain to return an AuditPageResponse deserialised from {@code json}. */
    @SuppressWarnings("unchecked")
    private <T> void stubPage(RestClient client, String json) {
        try {
            Class<T> type = (Class<T>)
                    Class.forName("com.robomart.notification.service.AuditAggregatorService$AuditPageResponse");
            T body = JSON.readValue(json, type);
            when(client.get().uri(anyString()).retrieve().body(type)).thenReturn(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stubEmpty(RestClient client) {
        stubPage(client, "{\"data\":[],\"pagination\":null,\"traceId\":\"x\"}");
    }

    private String entry(long id, String createdAt) {
        return "{\"id\":" + id + ",\"actor\":\"admin\",\"action\":\"CREATE\",\"entityType\":\"Order\","
                + "\"entityId\":\"" + id + "\",\"traceId\":\"t\",\"correlationId\":\"c\","
                + "\"createdAt\":\"" + createdAt + "\"}";
    }

    @Test
    void shouldAggregateAndSortAcrossServicesNewestFirst() {
        stubPage(orderClient, "{\"data\":[" + entry(1, "2026-01-01T10:00:00Z") + "],\"pagination\":null,\"traceId\":\"x\"}");
        stubPage(inventoryClient, "{\"data\":[" + entry(2, "2026-01-03T10:00:00Z") + "],\"pagination\":null,\"traceId\":\"x\"}");
        stubPage(paymentClient, "{\"data\":[" + entry(3, "2026-01-02T10:00:00Z") + "],\"pagination\":null,\"traceId\":\"x\"}");
        stubEmpty(productClient);

        AggregatedAuditResponse resp = service.getAuditLogs(
                null, null, null, null, null, null, null, 0, 10);

        assertThat(resp.totalElements()).isEqualTo(3);
        assertThat(resp.totalPages()).isEqualTo(1);
        assertThat(resp.data()).extracting(AuditLogEntry::id).containsExactly(2L, 3L, 1L);
    }

    @Test
    void shouldPaginateAggregatedResults() {
        stubPage(orderClient,
                "{\"data\":[" + entry(1, "2026-01-01T10:00:00Z") + "," + entry(2, "2026-01-02T10:00:00Z")
                        + "," + entry(3, "2026-01-03T10:00:00Z") + "],\"pagination\":null,\"traceId\":\"x\"}");
        stubEmpty(inventoryClient);
        stubEmpty(paymentClient);
        stubEmpty(productClient);

        AggregatedAuditResponse page0 = service.getAuditLogs(null, null, null, null, null, null, null, 0, 2);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.data()).hasSize(2);

        AggregatedAuditResponse page1 = service.getAuditLogs(null, null, null, null, null, null, null, 1, 2);
        assertThat(page1.data()).hasSize(1);
        assertThat(page1.page()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyPageWhenPageBeyondRange() {
        stubPage(orderClient, "{\"data\":[" + entry(1, "2026-01-01T10:00:00Z") + "],\"pagination\":null,\"traceId\":\"x\"}");
        stubEmpty(inventoryClient);
        stubEmpty(paymentClient);
        stubEmpty(productClient);

        AggregatedAuditResponse resp = service.getAuditLogs(null, null, null, null, null, null, null, 5, 10);

        assertThat(resp.totalElements()).isEqualTo(1);
        assertThat(resp.data()).isEmpty();
    }

    @Test
    void shouldBuildQueryStringWithAllFilters() {
        // All filter params non-null exercises every URLEncoder append branch in fetchAuditLogs.
        stubPage(orderClient, "{\"data\":[" + entry(7, "2026-01-01T10:00:00Z") + "],\"pagination\":null,\"traceId\":\"x\"}");
        stubEmpty(inventoryClient);
        stubEmpty(paymentClient);
        stubEmpty(productClient);

        AggregatedAuditResponse resp = service.getAuditLogs(
                "admin user", "CREATE", "Order", "7", "trace 1",
                "2026-01-01T00:00:00Z", "2026-01-31T00:00:00Z", 0, 10);

        assertThat(resp.totalElements()).isEqualTo(1);
        assertThat(resp.data().getFirst().id()).isEqualTo(7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldTolerateRestClientExceptionFromOneService() {
        stubPage(orderClient, "{\"data\":[" + entry(1, "2026-01-01T10:00:00Z") + "],\"pagination\":null,\"traceId\":\"x\"}");
        when(inventoryClient.get().uri(anyString()).retrieve().body(any(Class.class)))
                .thenThrow(new RestClientException("inventory down"));
        stubEmpty(paymentClient);
        stubEmpty(productClient);

        AggregatedAuditResponse resp = service.getAuditLogs(null, null, null, null, null, null, null, 0, 10);

        // The failing service is skipped; the rest still aggregate.
        assertThat(resp.totalElements()).isEqualTo(1);
        assertThat(resp.data().getFirst().id()).isEqualTo(1L);
    }

    @Test
    void shouldReturnEmptyWhenAllServicesReturnNothing() {
        stubEmpty(orderClient);
        stubEmpty(inventoryClient);
        stubEmpty(paymentClient);
        stubEmpty(productClient);

        AggregatedAuditResponse resp = service.getAuditLogs(null, null, null, null, null, null, null, 0, 10);

        assertThat(resp.totalElements()).isZero();
        assertThat(resp.totalPages()).isZero();
        assertThat(resp.data()).isEmpty();
    }

    @Test
    void initShouldBuildClientsWithoutError() {
        AuditAggregatorService fresh = new AuditAggregatorService();
        ReflectionTestUtils.setField(fresh, "orderUrl", "http://localhost:8083");
        ReflectionTestUtils.setField(fresh, "inventoryUrl", "http://localhost:8084");
        ReflectionTestUtils.setField(fresh, "paymentUrl", "http://localhost:8086");
        ReflectionTestUtils.setField(fresh, "productUrl", "http://localhost:8081");
        fresh.init();
        assertThat(ReflectionTestUtils.getField(fresh, "orderClient")).isNotNull();
    }
}
