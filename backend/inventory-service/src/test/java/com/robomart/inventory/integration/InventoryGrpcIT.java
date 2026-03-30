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
