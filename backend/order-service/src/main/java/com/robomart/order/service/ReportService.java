package com.robomart.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.web.ReportSummaryResponse;
import com.robomart.order.web.ReportSummaryResponse.OrderTrendEntry;
import com.robomart.order.web.ReportSummaryResponse.RevenueByProductEntry;
import com.robomart.order.web.ReportSummaryResponse.TopProductEntry;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;

    public ReportService(OrderItemRepository orderItemRepository, OrderRepository orderRepository) {
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public ReportSummaryResponse getSummary(Instant from, Instant to) {
        List<TopProductEntry> topProducts = orderItemRepository
                .findTopSellingProducts(from, to, PageRequest.of(0, 10))
                .stream()
                .map(row -> new TopProductEntry(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        ((Number) row[2]).longValue(),
                        row[3] != null ? (BigDecimal) row[3] : BigDecimal.ZERO))
                .toList();

        List<RevenueByProductEntry> revenueByProduct = orderItemRepository
                .findRevenueByProduct(from, to, PageRequest.of(0, 5))
                .stream()
                .map(row -> new RevenueByProductEntry(
                        (String) row[0],
                        row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO))
                .toList();

        List<OrderTrendEntry> orderTrends = orderRepository
                .findOrderTrends(from, to)
                .stream()
                .map(row -> new OrderTrendEntry(
                        row[0] != null ? row[0].toString() : "unknown",
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .toList();

        return new ReportSummaryResponse(topProducts, revenueByProduct, orderTrends);
    }

    public String rebuildReadModels() {
        String timestamp = Instant.now().toString();
        log.info("Rebuild triggered at {}", timestamp);
        return timestamp;
    }
}
