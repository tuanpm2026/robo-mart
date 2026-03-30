package com.robomart.inventory.unit.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.exception.InsufficientStockException;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryGrpcService Unit Tests")
class InventoryGrpcServiceTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private StreamObserver<ReserveInventoryResponse> reserveResponseObserver;

    @Mock
    private StreamObserver<ReleaseInventoryResponse> releaseResponseObserver;

    @Mock
    private StreamObserver<GetInventoryResponse> getInventoryResponseObserver;

    @Captor
    private ArgumentCaptor<ReserveInventoryResponse> reserveResponseCaptor;

    @Captor
    private ArgumentCaptor<ReleaseInventoryResponse> releaseResponseCaptor;

    @Captor
    private ArgumentCaptor<GetInventoryResponse> getInventoryResponseCaptor;

    @Captor
    private ArgumentCaptor<Throwable> throwableCaptor;

    private InventoryGrpcService inventoryGrpcService;

    @BeforeEach
    void setUp() {
        inventoryGrpcService = new InventoryGrpcService(inventoryService);
    }

    /**
     * Creates a test InventoryItem with the specified quantities.
     */
    private InventoryItem createTestInventoryItem(Long productId, int available, int reserved, int total) {
        InventoryItem item = new InventoryItem();
        item.setProductId(productId);
        item.setAvailableQuantity(available);
        item.setReservedQuantity(reserved);
        item.setTotalQuantity(total);
        item.setLowStockThreshold(10);
        return item;
    }

    @Nested
    @DisplayName("reserveInventory")
    class ReserveInventory {

        @Test
        @DisplayName("should return success when reservation succeeds")
        void shouldReturnSuccessWhenReservationSucceeds() {
            // given
            InventoryItem item = createTestInventoryItem(1L, 95, 5, 100);
            when(inventoryService.reserveStock(eq(1L), eq(5), eq("order-123")))
                    .thenReturn(item);

            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder()
                            .setProductId("1")
                            .setQuantity(5)
                            .build())
                    .build();

            // when
            inventoryGrpcService.reserveInventory(request, reserveResponseObserver);

            // then
            verify(reserveResponseObserver).onNext(reserveResponseCaptor.capture());
            verify(reserveResponseObserver).onCompleted();

            ReserveInventoryResponse response = reserveResponseCaptor.getValue();
            assertThat(response.getSuccess()).isTrue();
        }

        @Test
        @DisplayName("should return FAILED_PRECONDITION when insufficient stock")
        void shouldReturnFailedPreconditionWhenInsufficientStock() {
            // given
            when(inventoryService.reserveStock(eq(1L), eq(5), eq("order-123")))
                    .thenThrow(new InsufficientStockException(1L, 5, 3));

            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder()
                            .setProductId("1")
                            .setQuantity(5)
                            .build())
                    .build();

            // when
            inventoryGrpcService.reserveInventory(request, reserveResponseObserver);

            // then
            verify(reserveResponseObserver).onError(throwableCaptor.capture());
            Throwable error = throwableCaptor.getValue();
            assertThat(error).isInstanceOf(StatusRuntimeException.class);
            StatusRuntimeException statusException = (StatusRuntimeException) error;
            assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        }

        @Test
        @DisplayName("should return UNAVAILABLE when lock acquisition fails")
        void shouldReturnUnavailableWhenLockFails() {
            // given
            when(inventoryService.reserveStock(eq(1L), eq(5), eq("order-123")))
                    .thenThrow(new LockAcquisitionException(1L, "inventory:lock:product:1"));

            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder()
                            .setProductId("1")
                            .setQuantity(5)
                            .build())
                    .build();

            // when
            inventoryGrpcService.reserveInventory(request, reserveResponseObserver);

            // then
            verify(reserveResponseObserver).onError(throwableCaptor.capture());
            Throwable error = throwableCaptor.getValue();
            assertThat(error).isInstanceOf(StatusRuntimeException.class);
            StatusRuntimeException statusException = (StatusRuntimeException) error;
            assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        }

        @Test
        @DisplayName("should return NOT_FOUND when product does not exist")
        void shouldReturnNotFoundWhenProductNotExists() {
            // given
            when(inventoryService.reserveStock(eq(1L), eq(5), eq("order-123")))
                    .thenThrow(new ResourceNotFoundException("InventoryItem", 1L));

            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder()
                            .setProductId("1")
                            .setQuantity(5)
                            .build())
                    .build();

            // when
            inventoryGrpcService.reserveInventory(request, reserveResponseObserver);

            // then
            verify(reserveResponseObserver).onError(throwableCaptor.capture());
            Throwable error = throwableCaptor.getValue();
            assertThat(error).isInstanceOf(StatusRuntimeException.class);
            StatusRuntimeException statusException = (StatusRuntimeException) error;
            assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("releaseInventory")
    class ReleaseInventory {

        @Test
        @DisplayName("should return success when release succeeds")
        void shouldReturnSuccessWhenReleaseSucceeds() {
            // given
            InventoryItem item = createTestInventoryItem(1L, 95, 5, 100);
            when(inventoryService.releaseStock(eq(1L), eq(5), eq("order-123")))
                    .thenReturn(item);

            ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder()
                            .setProductId("1")
                            .setQuantity(5)
                            .build())
                    .build();

            // when
            inventoryGrpcService.releaseInventory(request, releaseResponseObserver);

            // then
            verify(releaseResponseObserver).onNext(releaseResponseCaptor.capture());
            verify(releaseResponseObserver).onCompleted();

            ReleaseInventoryResponse response = releaseResponseCaptor.getValue();
            assertThat(response.getSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("getInventory")
    class GetInventoryTests {

        @Test
        @DisplayName("should return inventory details when product exists")
        void shouldReturnInventoryDetailsOnGetInventory() {
            // given
            InventoryItem item = createTestInventoryItem(1L, 95, 5, 100);
            when(inventoryService.getInventory(eq(1L))).thenReturn(item);

            GetInventoryRequest request = GetInventoryRequest.newBuilder()
                    .setProductId("1")
                    .build();

            // when
            inventoryGrpcService.getInventory(request, getInventoryResponseObserver);

            // then
            verify(getInventoryResponseObserver).onNext(getInventoryResponseCaptor.capture());
            verify(getInventoryResponseObserver).onCompleted();

            GetInventoryResponse response = getInventoryResponseCaptor.getValue();
            assertThat(response.getProductId()).isEqualTo("1");
            assertThat(response.getAvailableQuantity()).isEqualTo(95);
            assertThat(response.getReservedQuantity()).isEqualTo(5);
            assertThat(response.getTotalQuantity()).isEqualTo(100);
        }
    }
}
