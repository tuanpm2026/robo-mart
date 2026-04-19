package com.robomart.order.unit.saga.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.grpc.InventoryGrpcClient;
import com.robomart.order.grpc.InventoryServiceUnavailableException;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.steps.ReleaseInventoryStep;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReleaseInventoryStep")
class ReleaseInventoryStepTest {

    @Mock
    private InventoryGrpcClient inventoryClient;

    private ReleaseInventoryStep step;

    @BeforeEach
    void setUp() {
        step = new ReleaseInventoryStep(inventoryClient);
    }

    @Test
    void shouldReturnCorrectName() {
        assertThat(step.getName()).isEqualTo("ReleaseInventory");
    }

    @Test
    void shouldThrowUnsupportedOperationExceptionOnExecute() {
        SagaContext context = buildContext(true);

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("compensation-only");
    }

    @Test
    void shouldSkipCompensateWhenNoReservationId() {
        SagaContext context = buildContext(false);

        step.compensate(context);

        verify(inventoryClient, never()).releaseInventory(any());
    }

    @Test
    void shouldReleaseInventoryWhenReservationIdPresent() {
        SagaContext context = buildContext(true);
        when(inventoryClient.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());

        step.compensate(context);

        verify(inventoryClient).releaseInventory(any(ReleaseInventoryRequest.class));
    }

    @Test
    void shouldLogAndContinueWhenReleaseThrowsStatusRuntimeException() {
        SagaContext context = buildContext(true);
        when(inventoryClient.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        // Should not throw — best-effort compensation
        step.compensate(context);
    }

    @Test
    void shouldLogAndContinueWhenReleaseThrowsInventoryServiceUnavailableException() {
        SagaContext context = buildContext(true);
        when(inventoryClient.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenThrow(new InventoryServiceUnavailableException("Inventory unavailable", null));

        // Should not throw — best-effort compensation
        step.compensate(context);
    }

    private SagaContext buildContext(boolean withReservation) {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId("user-1");
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatus.PENDING);
        if (withReservation) {
            order.setReservationId("res-abc-123");
        }

        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setProductName("Widget");
        item.setQuantity(3);
        item.setUnitPrice(new BigDecimal("33.33"));
        item.setSubtotal(new BigDecimal("100.00"));
        order.getItems().add(item);

        return new SagaContext(order);
    }
}
