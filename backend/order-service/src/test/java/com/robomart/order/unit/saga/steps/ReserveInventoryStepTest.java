package com.robomart.order.unit.saga.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.saga.steps.ReserveInventoryStep;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReserveInventoryStep")
class ReserveInventoryStepTest {

    @Mock
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    private ReserveInventoryStep step;

    @BeforeEach
    void setUp() {
        step = new ReserveInventoryStep(inventoryStub);
    }

    private Order buildOrder() {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId("user-1");
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(OrderStatus.INVENTORY_RESERVING);

        OrderItem item = new OrderItem();
        item.setProductId(10L);
        item.setProductName("Widget");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));
        order.setItems(new ArrayList<>(List.of(item)));
        return order;
    }

    @Test
    @DisplayName("shouldSetReservationIdWhenInventoryReservedSuccessfully")
    void shouldSetReservationIdWhenInventoryReservedSuccessfully() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class)))
                .thenReturn(ReserveInventoryResponse.newBuilder()
                        .setSuccess(true)
                        .setReservationId("res-123")
                        .build());

        step.execute(context);

        assertThat(order.getReservationId()).isEqualTo("res-123");
    }

    @Test
    @DisplayName("shouldThrowSagaStepExceptionWithoutCompensationWhenInsufficientStock")
    void shouldThrowSagaStepExceptionWithoutCompensationWhenInsufficientStock() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(inventoryStub.reserveInventory(any()))
                .thenThrow(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Insufficient stock")));

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class)
                .satisfies(e -> assertThat(((SagaStepException) e).isShouldCompensate()).isFalse());

        assertThat(order.getCancellationReason()).isEqualTo("Insufficient stock");
    }

    @Test
    @DisplayName("shouldThrowSagaStepExceptionWithCompensationOnOtherGrpcError")
    void shouldThrowSagaStepExceptionWithCompensationOnOtherGrpcError() {
        Order order = buildOrder();
        SagaContext context = new SagaContext(order);

        when(inventoryStub.reserveInventory(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Service unavailable")));

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(SagaStepException.class)
                .satisfies(e -> assertThat(((SagaStepException) e).isShouldCompensate()).isTrue());
    }

    @Test
    @DisplayName("shouldReturnNameReserveInventory")
    void shouldReturnNameReserveInventory() {
        assertThat(step.getName()).isEqualTo("ReserveInventory");
    }

    @Test
    @DisplayName("shouldBeNoOpOnCompensate")
    void shouldBeNoOpOnCompensate() {
        // compensate should not throw
        step.compensate(new SagaContext(buildOrder()));
    }
}
