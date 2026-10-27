package com.robomart.inventory.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.robomart.common.dto.ApiResponse;
import com.robomart.inventory.client.OrderStatusClient;
import com.robomart.inventory.service.InventoryService;
import com.robomart.inventory.service.ReservationExpiryJob;

class ReservationExpiryJobTest {
    @Test
    void oneUnavailableOrderDoesNotBlockOtherReservationsAndUncertainStockIsNeverReleased() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OrderStatusClient orders = mock(OrderStatusClient.class);
        InventoryService inventory = mock(InventoryService.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("1", "2", "3", "4"));
        when(orders.status("1")).thenThrow(new IllegalStateException("network failure"));
        when(orders.status("2")).thenReturn(new ApiResponse<>("PAYMENT_PENDING", null));
        when(orders.status("3")).thenReturn(new ApiResponse<>("DELIVERED", null));
        when(orders.status("4")).thenReturn(new ApiResponse<>("CANCELLED", null));
        var meters = new SimpleMeterRegistry();
        new ReservationExpiryJob(jdbc, orders, inventory, meters).sweep();
        meters.close();
        verify(inventory).commitReservation("3");
        verify(inventory).releaseReservation("4");
        verifyNoMoreInteractions(inventory);
        verify(jdbc, times(4)).update(anyString(), anyString());
    }
}
