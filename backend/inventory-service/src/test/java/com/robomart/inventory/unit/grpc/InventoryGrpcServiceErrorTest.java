package com.robomart.inventory.unit.grpc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.exception.LockAcquisitionException;
import com.robomart.inventory.grpc.InventoryGrpcService;
import com.robomart.inventory.service.InventoryService;
import com.robomart.proto.inventory.GetInventoryRequest;
import com.robomart.proto.inventory.GetInventoryResponse;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReservationItem;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryGrpcService Error Path Tests")
class InventoryGrpcServiceErrorTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private StreamObserver<ReserveInventoryResponse> reserveObserver;

    @Mock
    private StreamObserver<ReleaseInventoryResponse> releaseObserver;

    @Mock
    private StreamObserver<GetInventoryResponse> getObserver;

    @Captor
    private ArgumentCaptor<Throwable> errorCaptor;

    private InventoryGrpcService grpcService;

    @BeforeEach
    void setUp() {
        grpcService = new InventoryGrpcService(inventoryService);
    }

    @Nested
    @DisplayName("reserveInventory validation")
    class ReserveValidation {

        @Test
        void shouldReturnInvalidArgumentWhenOrderIdIsEmpty() {
            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(1).build())
                    .build();

            grpcService.reserveInventory(request, reserveObserver);

            verify(reserveObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnInvalidArgumentWhenNoItemsProvided() {
            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .build();

            grpcService.reserveInventory(request, reserveObserver);

            verify(reserveObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnAbortedWhenOptimisticLockFails() {
            when(inventoryService.reserveStock(anyLong(), anyInt(), anyString()))
                    .thenThrow(new ObjectOptimisticLockingFailureException("InventoryItem", 1L));

            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-999")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(2).build())
                    .build();

            grpcService.reserveInventory(request, reserveObserver);

            verify(reserveObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.ABORTED);
        }
    }

    @Nested
    @DisplayName("releaseInventory validation and errors")
    class ReleaseErrors {

        @Test
        void shouldReturnInvalidArgumentWhenOrderIdIsEmpty() {
            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(1).build())
                    .build();

            grpcService.releaseInventory(request, releaseObserver);

            verify(releaseObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnInvalidArgumentWhenNoItemsProvided() {
            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .build();

            grpcService.releaseInventory(request, releaseObserver);

            verify(releaseObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnNotFoundWhenProductNotFoundOnRelease() {
            when(inventoryService.releaseStock(eq(5L), eq(3), eq("order-rel")))
                    .thenThrow(new ResourceNotFoundException("InventoryItem", 5L));

            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-rel")
                    .addItems(ReservationItem.newBuilder().setProductId("5").setQuantity(3).build())
                    .build();

            grpcService.releaseInventory(request, releaseObserver);

            verify(releaseObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        void shouldReturnUnavailableWhenLockFailsOnRelease() {
            when(inventoryService.releaseStock(anyLong(), anyInt(), anyString()))
                    .thenThrow(new LockAcquisitionException(2L, "inventory:lock:product:2"));

            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-lock")
                    .addItems(ReservationItem.newBuilder().setProductId("2").setQuantity(1).build())
                    .build();

            grpcService.releaseInventory(request, releaseObserver);

            verify(releaseObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("reserveInventory with rollback")
    class ReserveWithRollback {

        @Test
        void shouldRollbackFirstItemWhenSecondItemReservationFails() {
            com.robomart.inventory.entity.InventoryItem item =
                    new com.robomart.inventory.entity.InventoryItem();
            item.setProductId(1L);
            item.setAvailableQuantity(90);
            item.setReservedQuantity(10);
            item.setTotalQuantity(100);
            item.setLowStockThreshold(5);

            when(inventoryService.reserveStock(eq(1L), eq(5), eq("order-multi")))
                    .thenReturn(item);
            when(inventoryService.reserveStock(eq(2L), eq(3), eq("order-multi")))
                    .thenThrow(new com.robomart.inventory.exception.InsufficientStockException(2L, 3, 1));

            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-multi")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(5).build())
                    .addItems(ReservationItem.newBuilder().setProductId("2").setQuantity(3).build())
                    .build();

            grpcService.reserveInventory(request, reserveObserver);

            verify(reserveObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION);
            verify(inventoryService).releaseStock(eq(1L), eq(5), eq("order-multi"));
        }
    }

    @Nested
    @DisplayName("getInventory errors")
    class GetInventoryErrors {

        @Test
        void shouldReturnNotFoundWhenProductNotFound() {
            when(inventoryService.getInventory(eq(99L)))
                    .thenThrow(new ResourceNotFoundException("InventoryItem", 99L));

            GetInventoryRequest request = GetInventoryRequest.newBuilder()
                    .setProductId("99")
                    .build();

            grpcService.getInventory(request, getObserver);

            verify(getObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        void shouldReturnInvalidArgumentWhenProductIdIsNotANumber() {
            GetInventoryRequest request = GetInventoryRequest.newBuilder()
                    .setProductId("not-a-number")
                    .build();

            grpcService.getInventory(request, getObserver);

            verify(getObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnInternalWhenUnexpectedExceptionOccurs() {
            when(inventoryService.getInventory(eq(1L)))
                    .thenThrow(new RuntimeException("Unexpected DB error"));

            GetInventoryRequest request = GetInventoryRequest.newBuilder()
                    .setProductId("1")
                    .build();

            grpcService.getInventory(request, getObserver);

            verify(getObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("reserveInventory additional error paths")
    class ReserveAdditionalErrors {

        @Test
        void shouldReturnInvalidArgumentWhenQuantityIsZero() {
            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(0).build())
                    .build();

            grpcService.reserveInventory(request, reserveObserver);

            verify(reserveObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnInternalWhenUnexpectedExceptionOccurs() {
            when(inventoryService.reserveStock(anyLong(), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("Unexpected error"));

            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(2).build())
                    .build();

            grpcService.reserveInventory(request, reserveObserver);

            verify(reserveObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("releaseInventory additional error paths")
    class ReleaseAdditionalErrors {

        @Test
        void shouldReturnInvalidArgumentWhenQuantityIsZero() {
            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(0).build())
                    .build();

            grpcService.releaseInventory(request, releaseObserver);

            verify(releaseObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnAbortedWhenOptimisticLockFailsOnRelease() {
            when(inventoryService.releaseStock(anyLong(), anyInt(), anyString()))
                    .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException("InventoryItem", 1L));

            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(2).build())
                    .build();

            grpcService.releaseInventory(request, releaseObserver);

            verify(releaseObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.ABORTED);
        }

        @Test
        void shouldReturnInternalWhenUnexpectedExceptionOccurs() {
            when(inventoryService.releaseStock(anyLong(), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("Unexpected error"));

            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(2).build())
                    .build();

            grpcService.releaseInventory(request, releaseObserver);

            verify(releaseObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }
    }
}
