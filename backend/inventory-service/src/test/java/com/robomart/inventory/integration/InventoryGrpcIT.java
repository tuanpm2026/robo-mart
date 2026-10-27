package com.robomart.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.entity.Reservation;
import com.robomart.inventory.entity.ReservationLine;
import com.robomart.inventory.enums.ReservationStatus;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.ReservationLineRepository;
import com.robomart.inventory.repository.ReservationRepository;
import com.robomart.inventory.service.InventoryService;
import com.robomart.proto.inventory.GetInventoryRequest;
import com.robomart.proto.inventory.GetInventoryResponse;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReservationItem;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;
import com.robomart.test.InventoryIntegrationTest;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Integration tests for the Inventory gRPC endpoints with real PostgreSQL, Kafka, and gRPC server
 * via Testcontainers.
 *
 * <p>These tests verify end-to-end gRPC communication including request/response serialization,
 * error status code mapping, and correct service behavior through the gRPC layer.
 *
 * <p>Uses {@code @TestPropertySource} to set {@code spring.grpc.server.port=0} for random port
 * allocation in tests, and {@code GrpcServerLifecycle.getPort()} to obtain the assigned port.
 */
@InventoryIntegrationTest
@TestPropertySource(properties = "spring.grpc.server.port=0")
class InventoryGrpcIT {

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationLineRepository reservationLineRepository;

    @MockitoSpyBean
    private InventoryService inventoryService;

    private ManagedChannel channel;
    private InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        // Create a plaintext gRPC channel to the test server
        int grpcPort = grpcServerLifecycle.getPort();
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        stub = InventoryServiceGrpc.newBlockingStub(channel);


    }

    @AfterEach
    void tearDown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
        }
    }

    @Test
    void shouldReserveInventoryViaGrpc() {
        // Arrange: use product_id=5 (seed available=75)
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId("grpc-reserve-order-1")
                .addItems(ReservationItem.newBuilder()
                        .setProductId("5")
                        .setQuantity(2)
                        .build())
                .build();

        // Act
        ReserveInventoryResponse response = stub.reserveInventory(request);

        // Assert
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getReservationId()).isNotEmpty();
        assertThat(response.getMessage()).isNotEmpty();
    }

    @Test
    void shouldReturnFailedPreconditionForInsufficientStock() {
        // Arrange: product_id=49 has available_quantity=0 in seed data
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId("grpc-insufficient-order")
                .addItems(ReservationItem.newBuilder()
                        .setProductId("49")
                        .setQuantity(100)
                        .build())
                .build();

        // Act & Assert
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.reserveInventory(request));

        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void shouldReturnNotFoundForNonExistentProduct() {
        // Arrange: product_id=99999 does not exist
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId("grpc-not-found-order")
                .addItems(ReservationItem.newBuilder()
                        .setProductId("99999")
                        .setQuantity(1)
                        .build())
                .build();

        // Act & Assert
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.reserveInventory(request));

        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void shouldReleaseInventoryViaGrpc() {
        // Arrange: first reserve some stock for product_id=6 (seed available=310)
        String orderId = "grpc-release-order-1";
        ReserveInventoryRequest reserveRequest = ReserveInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder()
                        .setProductId("6")
                        .setQuantity(3)
                        .build())
                .build();
        ReserveInventoryResponse reserveResponse = stub.reserveInventory(reserveRequest);
        assertThat(reserveResponse.getSuccess()).isTrue();

        // Act: release the reserved stock
        ReleaseInventoryRequest releaseRequest = ReleaseInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reserveResponse.getReservationId())
                .addItems(ReservationItem.newBuilder()
                        .setProductId("6")
                        .setQuantity(3)
                        .build())
                .build();
        ReleaseInventoryResponse releaseResponse = stub.releaseInventory(releaseRequest);

        // Assert
        assertThat(releaseResponse.getSuccess()).isTrue();
        assertThat(releaseResponse.getMessage()).isNotEmpty();
    }

    @Test
    void shouldGetInventoryViaGrpc() {
        AtomicBoolean virtualThread = new AtomicBoolean();
        doAnswer(invocation -> {
            virtualThread.set(Thread.currentThread().isVirtual());
            return invocation.callRealMethod();
        }).when(inventoryService).getInventory(8L);
        // Arrange: product_id=8 has available=500 in seed data
        GetInventoryRequest request = GetInventoryRequest.newBuilder()
                .setProductId("8")
                .build();

        // Act
        GetInventoryResponse response = stub.getInventory(request);

        // Assert
        assertThat(virtualThread).as("The actual gRPC service executes on a virtual thread").isTrue();
        assertThat(response.getProductId()).isEqualTo("8");
        assertThat(response.getAvailableQuantity()).isGreaterThan(0);
        assertThat(response.getTotalQuantity()).isGreaterThan(0);
    }

    @Test
    void shouldReplayReservationOnDuplicateReserveViaGrpc() {
        // Arrange: a controlled stock level on a dedicated product
        Long productId = 11L;
        InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
        item.setAvailableQuantity(100);
        item.setReservedQuantity(0);
        inventoryItemRepository.save(item);

        String orderId = "grpc-idem-reserve-1";
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("11").setQuantity(2).build())
                .build();

        // Act: reserve, then reserve again for the SAME orderId (simulates a retry/replay)
        ReserveInventoryResponse first = stub.reserveInventory(request);
        ReserveInventoryResponse second = stub.reserveInventory(request);

        // Assert: same reservationId replayed, stock decremented exactly once
        assertThat(first.getReservationId()).isNotEmpty();
        assertThat(second.getReservationId()).isEqualTo(first.getReservationId());

        InventoryItem after = inventoryItemRepository.findByProductId(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(98);
        assertThat(after.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    void shouldBeIdempotentOnDuplicateReleaseViaGrpc() {
        // Arrange: a controlled stock level on a dedicated product
        Long productId = 12L;
        InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
        item.setAvailableQuantity(100);
        item.setReservedQuantity(0);
        inventoryItemRepository.save(item);

        String orderId = "grpc-idem-release-1";
        ReserveInventoryResponse reserveResponse = stub.reserveInventory(ReserveInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("12").setQuantity(3).build())
                .build());

        ReleaseInventoryRequest releaseRequest = ReleaseInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reserveResponse.getReservationId())
                .addItems(ReservationItem.newBuilder().setProductId("12").setQuantity(3).build())
                .build();

        // Act: release twice for the same orderId (simulates compensation retry)
        ReleaseInventoryResponse firstRelease = stub.releaseInventory(releaseRequest);
        ReleaseInventoryResponse secondRelease = stub.releaseInventory(releaseRequest);

        // Assert: both succeed, stock restored exactly once (no over-release)
        assertThat(firstRelease.getSuccess()).isTrue();
        assertThat(secondRelease.getSuccess()).isTrue();

        InventoryItem after = inventoryItemRepository.findByProductId(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(100);
        assertThat(after.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void shouldReleaseByOrderIdWithoutReservationId() {
        // Arrange: reserve so there is a reservation record, then release WITHOUT a reservationId —
        // mirrors the order-service compensation path after a ReserveInventory step timeout.
        Long productId = 13L;
        InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
        item.setAvailableQuantity(100);
        item.setReservedQuantity(0);
        inventoryItemRepository.save(item);

        String orderId = "grpc-release-by-order-1";
        stub.reserveInventory(ReserveInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("13").setQuantity(4).build())
                .build());

        // Act: release with order_id + items but NO reservation_id
        ReleaseInventoryResponse response = stub.releaseInventory(ReleaseInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("13").setQuantity(4).build())
                .build());

        // Assert: released successfully back to the original level
        assertThat(response.getSuccess()).isTrue();
        InventoryItem after = inventoryItemRepository.findByProductId(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(100);
        assertThat(after.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void shouldTombstoneReleaseWithoutReservationAndBlockLateReserve() {
        // Arrange: another order legitimately holds 4 units of the product
        Long productId = 14L;
        resetStock(productId, 100);
        stub.reserveInventory(ReserveInventoryRequest.newBuilder()
                .setOrderId("grpc-tomb-other-order")
                .addItems(ReservationItem.newBuilder().setProductId("14").setQuantity(4).build())
                .build());

        // Act: the saga compensates an order whose reserve never happened (e.g. reserve was
        // UNAVAILABLE). Its release request lists items, which must NOT be released.
        String orderId = "grpc-tomb-order-1";
        ReleaseInventoryResponse release = stub.releaseInventory(ReleaseInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("14").setQuantity(4).build())
                .build());

        // Assert: success, nothing released (the other order's 4 units stay reserved), tombstone stored
        assertThat(release.getSuccess()).isTrue();
        assertStock(productId, 96, 4);
        Reservation tombstone = reservationRepository.findByOrderId(orderId).orElseThrow();
        assertThat(tombstone.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservationLineRepository.findByOrderIdOrderByProductIdAsc(orderId)).isEmpty();

        // Act: the original reserve arrives late for the same order
        StatusRuntimeException late = assertThrows(StatusRuntimeException.class,
                () -> stub.reserveInventory(ReserveInventoryRequest.newBuilder()
                        .setOrderId(orderId)
                        .addItems(ReservationItem.newBuilder().setProductId("14").setQuantity(2).build())
                        .build()));

        // Assert: rejected and no stock reserved for it
        assertThat(late.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertStock(productId, 96, 4);
    }

    @Test
    void shouldRestoreStockExactlyOnceUnderConcurrentReleases() throws Exception {
        // Arrange
        Long productId = 15L;
        resetStock(productId, 100);
        String orderId = "grpc-concurrent-release-1";
        ReserveInventoryResponse reserved = stub.reserveInventory(ReserveInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("15").setQuantity(7).build())
                .build());
        assertStock(productId, 93, 7);

        ReleaseInventoryRequest releaseRequest = ReleaseInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reserved.getReservationId())
                .addItems(ReservationItem.newBuilder().setProductId("15").setQuantity(7).build())
                .build();

        // Act: saga compensation, recovery and retries all release the same order at once
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReleaseInventoryResponse>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                start.await();
                return stub.releaseInventory(releaseRequest);
            }));
        }
        start.countDown();
        for (Future<ReleaseInventoryResponse> result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS).getSuccess()).isTrue();
        }
        executor.shutdown();

        // Assert: restored exactly once
        assertStock(productId, 100, 0);
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void shouldReleaseStoredLinesNotRequestItems() {
        // Arrange: reserve 2 products; another order also holds units of a third product
        resetStock(16L, 100);
        resetStock(17L, 100);
        resetStock(18L, 100);
        stub.reserveInventory(ReserveInventoryRequest.newBuilder()
                .setOrderId("grpc-lines-other-order")
                .addItems(ReservationItem.newBuilder().setProductId("18").setQuantity(9).build())
                .build());
        String orderId = "grpc-lines-order-1";
        stub.reserveInventory(ReserveInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("16").setQuantity(2).build())
                .addItems(ReservationItem.newBuilder().setProductId("17").setQuantity(3).build())
                .addItems(ReservationItem.newBuilder().setProductId("16").setQuantity(1).build())
                .build());
        assertThat(reservationLineRepository.findByOrderIdOrderByProductIdAsc(orderId))
                .extracting(ReservationLine::getProductId, ReservationLine::getQuantity)
                .containsExactly(tuple(16L, 3), tuple(17L, 3));

        // Act: release with items that differ from what was reserved (wrong quantity, a product that
        // belongs to another order, one reserved product missing)
        ReleaseInventoryResponse response = stub.releaseInventory(ReleaseInventoryRequest.newBuilder()
                .setOrderId(orderId)
                .addItems(ReservationItem.newBuilder().setProductId("16").setQuantity(50).build())
                .addItems(ReservationItem.newBuilder().setProductId("18").setQuantity(9).build())
                .build());

        // Assert: exactly the stored lines were restored; the other order's units are untouched
        assertThat(response.getSuccess()).isTrue();
        assertStock(16L, 100, 0);
        assertStock(17L, 100, 0);
        assertStock(18L, 91, 9);
    }

    @Test
    void shouldReturnInvalidArgumentForBadProductId() {
        // Arrange: product_id is not a valid number
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId("grpc-invalid-order")
                .addItems(ReservationItem.newBuilder()
                        .setProductId("not-a-number")
                        .setQuantity(1)
                        .build())
                .build();

        // Act & Assert
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.reserveInventory(request));

        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private void resetStock(Long productId, int available) {
        InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
        item.setAvailableQuantity(available);
        item.setReservedQuantity(0);
        inventoryItemRepository.save(item);
    }

    private void assertStock(Long productId, int available, int reserved) {
        InventoryItem item = inventoryItemRepository.findByProductId(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).as("available of product %d", productId).isEqualTo(available);
        assertThat(item.getReservedQuantity()).as("reserved of product %d", productId).isEqualTo(reserved);
    }
}
