package com.robomart.inventory.integration;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.TestPropertySource;

import com.robomart.inventory.config.RedisLockConfig;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.proto.inventory.GetInventoryRequest;
import com.robomart.proto.inventory.GetInventoryResponse;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReservationItem;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;
import com.robomart.test.IntegrationTest;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the Inventory gRPC endpoints with real PostgreSQL, Redis, and gRPC server
 * via Testcontainers.
 *
 * <p>These tests verify end-to-end gRPC communication including request/response serialization,
 * error status code mapping, and correct service behavior through the gRPC layer.
 *
 * <p>Uses {@code @TestPropertySource} to set {@code spring.grpc.server.port=0} for random port
 * allocation in tests, and {@code GrpcServerLifecycle.getPort()} to obtain the assigned port.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.grpc.server.port=0")
class InventoryGrpcIT {

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

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

        // Clean up any leftover Redis lock keys
        Set<String> lockKeys = stringRedisTemplate.keys(RedisLockConfig.LOCK_KEY_PREFIX + "*");
        if (lockKeys != null && !lockKeys.isEmpty()) {
            stringRedisTemplate.delete(lockKeys);
        }
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
        // Arrange: product_id=8 has available=500 in seed data
        GetInventoryRequest request = GetInventoryRequest.newBuilder()
                .setProductId("8")
                .build();

        // Act
        GetInventoryResponse response = stub.getInventory(request);

        // Assert
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
}
