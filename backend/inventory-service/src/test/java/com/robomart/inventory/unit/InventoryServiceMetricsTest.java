package com.robomart.inventory.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.inventory.dto.InventoryMetricsResponse;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.OutboxEventRepository;
import com.robomart.inventory.repository.StockMovementRepository;
import com.robomart.inventory.service.DistributedLockService;
import com.robomart.inventory.service.InventoryService;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryService - Metrics")
class InventoryServiceMetricsTest {

    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ObjectMapper objectMapper;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(
                inventoryItemRepository, stockMovementRepository, outboxEventRepository,
                distributedLockService, transactionTemplate, objectMapper);
    }

    @Test
    @DisplayName("shouldReturnLowStockCountWhenSomeItemsBelowThreshold")
    void shouldReturnLowStockCountWhenSomeItemsBelowThreshold() {
        when(inventoryItemRepository.countLowStockItems()).thenReturn(3L);

        InventoryMetricsResponse result = inventoryService.getMetrics();

        assertThat(result.lowStockCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("shouldReturnZeroWhenAllItemsAboveThreshold")
    void shouldReturnZeroWhenAllItemsAboveThreshold() {
        when(inventoryItemRepository.countLowStockItems()).thenReturn(0L);

        InventoryMetricsResponse result = inventoryService.getMetrics();

        assertThat(result.lowStockCount()).isEqualTo(0L);
    }
}
