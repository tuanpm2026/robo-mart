package com.robomart.inventory.unit.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.common.dto.ApiResponse;
import com.robomart.inventory.controller.InventoryAdminRestController;
import com.robomart.inventory.dto.InventoryItemResponse;
import com.robomart.inventory.dto.InventoryMetricsResponse;
import com.robomart.inventory.dto.PagedInventoryResponse;
import com.robomart.inventory.dto.RestockRequest;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.service.AuditLogService;
import com.robomart.inventory.service.InventoryService;
import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryControllerTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private Tracer tracer;

    private InventoryAdminRestController controller;

    @BeforeEach
    void setUp() {
        controller = new InventoryAdminRestController(inventoryService, auditLogService, tracer);
    }

    @Test
    void shouldReturnMetricsWhenGetMetricsCalled() {
        InventoryMetricsResponse metrics = new InventoryMetricsResponse(3L);
        when(inventoryService.getMetrics()).thenReturn(metrics);

        ResponseEntity<ApiResponse<InventoryMetricsResponse>> response = controller.getMetrics();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().lowStockCount()).isEqualTo(3L);
    }

    @Test
    void shouldReturnPagedInventoryWhenListInventoryCalled() {
        InventoryItem item = buildInventoryItem(1L, 10L, 100);
        Page<InventoryItem> page = new PageImpl<>(List.of(item), PageRequest.of(0, 25), 1);
        when(inventoryService.listInventory(any())).thenReturn(page);

        ResponseEntity<PagedInventoryResponse> response = controller.listInventory(0, 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).hasSize(1);
    }

    @Test
    void shouldReturnUpdatedItemWhenRestockCalled() {
        InventoryItem restocked = buildInventoryItem(1L, 10L, 150);
        when(inventoryService.restockItem(eq(10L), eq(50), eq("Manual restock"))).thenReturn(restocked);

        RestockRequest request = new RestockRequest(50, "Manual restock");
        ResponseEntity<ApiResponse<InventoryItemResponse>> response = controller.restockItem(10L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    private InventoryItem buildInventoryItem(Long id, Long productId, Integer available) {
        InventoryItem item = new InventoryItem();
        item.setProductId(productId);
        item.setAvailableQuantity(available);
        item.setReservedQuantity(0);
        item.setTotalQuantity(available);
        item.setLowStockThreshold(10);
        return item;
    }
}
