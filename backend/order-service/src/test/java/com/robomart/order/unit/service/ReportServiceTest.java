package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.service.ReportService;
import com.robomart.order.web.ReportSummaryResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService")
class ReportServiceTest {

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private ReportService reportService;

    @Test
    @DisplayName("getSummary returns mapped DTOs from query results")
    void getSummary_returnsTopProducts() {
        Instant from = Instant.now().minusSeconds(86400);
        Instant to = Instant.now();

        Object[] topRow = {1L, "Product A", 10L, new BigDecimal("99.90")};
        Object[] revenueRow = {"Product A", new BigDecimal("99.90")};
        Object[] trendRow = {"2026-04-10", "CONFIRMED", 5L};

        List<Object[]> topRows = Collections.singletonList(topRow);
        List<Object[]> revenueRows = Collections.singletonList(revenueRow);
        List<Object[]> trendRows = Collections.singletonList(trendRow);

        when(orderItemRepository.findTopSellingProducts(any(), any(), any())).thenReturn(topRows);
        when(orderItemRepository.findRevenueByProduct(any(), any(), any())).thenReturn(revenueRows);
        when(orderRepository.findOrderTrends(any(), any())).thenReturn(trendRows);

        ReportSummaryResponse result = reportService.getSummary(from, to);

        assertThat(result.topProducts()).hasSize(1);
        assertThat(result.topProducts().get(0).productName()).isEqualTo("Product A");
        assertThat(result.topProducts().get(0).totalQuantity()).isEqualTo(10L);
        assertThat(result.revenueByProduct()).hasSize(1);
        assertThat(result.orderTrends()).hasSize(1);
        assertThat(result.orderTrends().get(0).status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("getSummary with empty results returns empty lists")
    void getSummary_emptyResults_returnsEmptyLists() {
        Instant from = Instant.now().minusSeconds(86400);
        Instant to = Instant.now();

        List<Object[]> empty = new ArrayList<>();
        when(orderItemRepository.findTopSellingProducts(any(), any(), any())).thenReturn(empty);
        when(orderItemRepository.findRevenueByProduct(any(), any(), any())).thenReturn(empty);
        when(orderRepository.findOrderTrends(any(), any())).thenReturn(empty);

        ReportSummaryResponse result = reportService.getSummary(from, to);

        assertThat(result.topProducts()).isEmpty();
        assertThat(result.revenueByProduct()).isEmpty();
        assertThat(result.orderTrends()).isEmpty();
    }

    @Test
    @DisplayName("rebuildReadModels returns timestamp string")
    void rebuildReadModels_returnsTimestampString() {
        String result = reportService.rebuildReadModels();

        assertThat(result).isNotNull();
        // Should be parseable as ISO instant
        assertThat(Instant.parse(result)).isNotNull();
    }
}
