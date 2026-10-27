package com.robomart.inventory.unit.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

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
import com.robomart.inventory.entity.Reservation;
import com.robomart.inventory.enums.ReservationStatus;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.exception.ReservationReleasedException;
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

    private static ReserveInventoryRequest reserveRequest(String productId, int quantity) {
        return ReserveInventoryRequest.newBuilder()
                .setOrderId("order-123")
                .addItems(ReservationItem.newBuilder()
                        .setProductId(productId)
                        .setQuantity(quantity)
                        .build())
                .build();
    }

    private Status.Code reserveErrorCode() {
        verify(reserveResponseObserver).onError(throwableCaptor.capture());
        Throwable error = throwableCaptor.getValue();
        assertThat(error).isInstanceOf(StatusRuntimeException.class);
        return ((StatusRuntimeException) error).getStatus().getCode();
    }

    @Nested
    @DisplayName("reserveInventory")
    class ReserveInventory {

        @Test
        @DisplayName("should return success when reservation succeeds")
        void shouldReturnSuccessWhenReservationSucceeds() {
            // given
            when(inventoryService.reserveOrder(eq("order-123"), eq(Map.of(1L, 5))))
                    .thenReturn(new InventoryService.ReservationResult("res-1", false));

            // when
            inventoryGrpcService.reserveInventory(reserveRequest("1", 5), reserveResponseObserver);

            // then
            verify(reserveResponseObserver).onNext(reserveResponseCaptor.capture());
            verify(reserveResponseObserver).onCompleted();

            ReserveInventoryResponse response = reserveResponseCaptor.getValue();
            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getReservationId()).isEqualTo("res-1");
            assertThat(response.getMessage()).isEqualTo("Inventory reserved successfully");
        }

        @Test
        @DisplayName("should merge duplicate product lines into one reservation line")
        void shouldMergeDuplicateProductLines() {
            // given
            when(inventoryService.reserveOrder(eq("order-123"), eq(Map.of(1L, 5, 2L, 1))))
                    .thenReturn(new InventoryService.ReservationResult("res-1", false));
            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(2).build())
                    .addItems(ReservationItem.newBuilder().setProductId("2").setQuantity(1).build())
                    .addItems(ReservationItem.newBuilder().setProductId("1").setQuantity(3).build())
                    .build();

            // when
            inventoryGrpcService.reserveInventory(request, reserveResponseObserver);

            // then
            verify(reserveResponseObserver).onCompleted();
        }

        @Test
        @DisplayName("should return FAILED_PRECONDITION when insufficient stock")
        void shouldReturnFailedPreconditionWhenInsufficientStock() {
            when(inventoryService.reserveOrder(eq("order-123"), anyMap()))
                    .thenThrow(new InsufficientStockException(1L, 5, 3));

            inventoryGrpcService.reserveInventory(reserveRequest("1", 5), reserveResponseObserver);

            assertThat(reserveErrorCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        }



        @Test
        @DisplayName("should return NOT_FOUND when product does not exist")
        void shouldReturnNotFoundWhenProductNotExists() {
            when(inventoryService.reserveOrder(eq("order-123"), anyMap()))
                    .thenThrow(new ResourceNotFoundException("InventoryItem", 1L));

            inventoryGrpcService.reserveInventory(reserveRequest("1", 5), reserveResponseObserver);

            assertThat(reserveErrorCode()).isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        @DisplayName("should replay existing reservation without re-reserving stock (idempotent)")
        void shouldReplayExistingReservation() {
            // given an order that was already reserved (e.g. a saga-step timeout is now retrying)
            Reservation existing = new Reservation("existing-res-id", "order-123", ReservationStatus.RESERVED);
            when(inventoryService.findReservation("order-123")).thenReturn(Optional.of(existing));

            // when
            inventoryGrpcService.reserveInventory(reserveRequest("1", 5), reserveResponseObserver);

            // then — the original reservationId is replayed and stock is NOT decremented again
            verify(reserveResponseObserver).onNext(reserveResponseCaptor.capture());
            verify(reserveResponseObserver).onCompleted();
            assertThat(reserveResponseCaptor.getValue().getReservationId()).isEqualTo("existing-res-id");
            verify(inventoryService, never()).reserveOrder(anyString(), anyMap());
        }

        @Test
        @DisplayName("should replay when the service detects the reservation under its transaction")
        void shouldReplayWhenServiceReportsReplay() {
            when(inventoryService.reserveOrder(eq("order-123"), anyMap()))
                    .thenReturn(new InventoryService.ReservationResult("winner-res-id", true));

            inventoryGrpcService.reserveInventory(reserveRequest("1", 5), reserveResponseObserver);

            verify(reserveResponseObserver).onNext(reserveResponseCaptor.capture());
            assertThat(reserveResponseCaptor.getValue().getReservationId()).isEqualTo("winner-res-id");
            assertThat(reserveResponseCaptor.getValue().getMessage()).isEqualTo("Inventory already reserved");
        }

        @Test
        @DisplayName("should reject reserve when the order's reservation was already released")
        void shouldRejectReserveWhenAlreadyReleased() {
            Reservation released = new Reservation("res-id", "order-123", ReservationStatus.RELEASED);
            when(inventoryService.findReservation("order-123")).thenReturn(Optional.of(released));

            inventoryGrpcService.reserveInventory(reserveRequest("1", 5), reserveResponseObserver);

            assertThat(reserveErrorCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            verify(inventoryService, never()).reserveOrder(anyString(), anyMap());
        }

        @Test
        @DisplayName("should reject reserve when a release tombstone wins inside the transaction")
        void shouldRejectReserveWhenServiceReportsReleased() {
            when(inventoryService.reserveOrder(eq("order-123"), anyMap()))
                    .thenThrow(new ReservationReleasedException("order-123"));

            inventoryGrpcService.reserveInventory(reserveRequest("1", 5), reserveResponseObserver);

            assertThat(reserveErrorCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        }
    }

    @Nested
    @DisplayName("releaseInventory")
    class ReleaseInventory {

        private ReleaseInventoryRequest releaseRequest() {
            return ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder()
                            .setProductId("1")
                            .setQuantity(5)
                            .build())
                    .build();
        }

        @Test
        @DisplayName("should release the stored reservation, not the request items")
        void shouldReturnSuccessWhenReleaseSucceeds() {
            // given
            when(inventoryService.releaseReservation("order-123"))
                    .thenReturn(InventoryService.ReleaseOutcome.RELEASED);

            // when
            inventoryGrpcService.releaseInventory(releaseRequest(), releaseResponseObserver);

            // then
            verify(releaseResponseObserver).onNext(releaseResponseCaptor.capture());
            verify(releaseResponseObserver).onCompleted();

            ReleaseInventoryResponse response = releaseResponseCaptor.getValue();
            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("Inventory released successfully");
        }

        @Test
        @DisplayName("should be an idempotent no-op when the reservation is already released")
        void shouldBeNoOpWhenAlreadyReleased() {
            when(inventoryService.releaseReservation("order-123"))
                    .thenReturn(InventoryService.ReleaseOutcome.ALREADY_RELEASED);

            inventoryGrpcService.releaseInventory(releaseRequest(), releaseResponseObserver);

            verify(releaseResponseObserver).onNext(releaseResponseCaptor.capture());
            verify(releaseResponseObserver).onCompleted();
            assertThat(releaseResponseCaptor.getValue().getSuccess()).isTrue();
            assertThat(releaseResponseCaptor.getValue().getMessage()).isEqualTo("Inventory already released");
        }

        @Test
        @DisplayName("should succeed without releasing anything when the order has no reservation")
        void shouldSucceedWhenNoReservation() {
            when(inventoryService.releaseReservation("order-123"))
                    .thenReturn(InventoryService.ReleaseOutcome.NO_RESERVATION);

            inventoryGrpcService.releaseInventory(releaseRequest(), releaseResponseObserver);

            verify(releaseResponseObserver).onNext(releaseResponseCaptor.capture());
            assertThat(releaseResponseCaptor.getValue().getSuccess()).isTrue();
            assertThat(releaseResponseCaptor.getValue().getMessage()).contains("No reservation found");
        }

        @Test
        @DisplayName("should ignore request items entirely (none, or invalid ones)")
        void shouldIgnoreRequestItems() {
            when(inventoryService.releaseReservation("order-123"))
                    .thenReturn(InventoryService.ReleaseOutcome.RELEASED);
            ReleaseInventoryRequest noItems = ReleaseInventoryRequest.newBuilder().setOrderId("order-123").build();
            ReleaseInventoryRequest badItems = ReleaseInventoryRequest.newBuilder()
                    .setOrderId("order-123")
                    .addItems(ReservationItem.newBuilder().setProductId("x").setQuantity(0).build())
                    .build();

            inventoryGrpcService.releaseInventory(noItems, releaseResponseObserver);
            inventoryGrpcService.releaseInventory(badItems, releaseResponseObserver);

            verify(releaseResponseObserver, times(2)).onCompleted();
            verify(inventoryService, times(2)).releaseReservation("order-123");
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
