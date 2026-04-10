package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.service.OrderService;
import com.robomart.order.web.OrderDashboardMetricsResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService - Dashboard Metrics")
class OrderServiceDashboardTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock private OrderSagaOrchestrator orderSagaOrchestrator;
    @Mock private TransactionTemplate transactionTemplate;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository, orderItemRepository, orderStatusHistoryRepository,
                orderSagaOrchestrator, transactionTemplate);
    }

    @Test
    @DisplayName("shouldReturnOrderCountAndRevenueForToday")
    void shouldReturnOrderCountAndRevenueForToday() {
        when(orderRepository.countByCreatedAtAfter(any())).thenReturn(5L);
        when(orderRepository.sumTotalAmountByCreatedAtAfter(any()))
                .thenReturn(new BigDecimal("250.00"));

        OrderDashboardMetricsResponse result = orderService.getDashboardMetrics();

        assertThat(result.ordersToday()).isEqualTo(5L);
        assertThat(result.revenueToday()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("shouldReturnZeroSumWhenNoOrdersToday")
    void shouldReturnZeroSumWhenNoOrdersToday() {
        when(orderRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(orderRepository.sumTotalAmountByCreatedAtAfter(any()))
                .thenReturn(BigDecimal.ZERO);

        OrderDashboardMetricsResponse result = orderService.getDashboardMetrics();

        assertThat(result.ordersToday()).isEqualTo(0L);
        assertThat(result.revenueToday()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("shouldReturnMultipleOrdersWithLargeRevenue")
    void shouldReturnMultipleOrdersWithLargeRevenue() {
        when(orderRepository.countByCreatedAtAfter(any())).thenReturn(42L);
        when(orderRepository.sumTotalAmountByCreatedAtAfter(any()))
                .thenReturn(new BigDecimal("9999.99"));

        OrderDashboardMetricsResponse result = orderService.getDashboardMetrics();

        assertThat(result.ordersToday()).isEqualTo(42L);
        assertThat(result.revenueToday()).isEqualByComparingTo("9999.99");
    }
}
