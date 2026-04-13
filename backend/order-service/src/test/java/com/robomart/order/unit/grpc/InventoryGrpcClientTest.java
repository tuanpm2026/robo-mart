package com.robomart.order.unit.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.order.grpc.InventoryGrpcClient;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryGrpcClient")
class InventoryGrpcClientTest {

    @Mock
    private InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    private InventoryGrpcClient client;

    @BeforeEach
    void setUp() {
        client = new InventoryGrpcClient(stub);
    }

    @Test
    @DisplayName("shouldReturnResponseWhenReserveInventorySucceeds")
    void shouldReturnResponseWhenReserveInventorySucceeds() {
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId("order-1")
                .build();

        when(stub.reserveInventory(any(ReserveInventoryRequest.class)))
                .thenReturn(ReserveInventoryResponse.newBuilder()
                        .setSuccess(true)
                        .setReservationId("res-123")
                        .build());

        ReserveInventoryResponse response = client.reserveInventory(request);

        assertThat(response.getReservationId()).isEqualTo("res-123");
        assertThat(response.getSuccess()).isTrue();
    }

    @Test
    @DisplayName("shouldPropagateStatusRuntimeExceptionWhenStubThrows_unitTestNoteAopBypassed")
    void shouldPropagateStatusRuntimeExceptionWhenStubThrows_unitTestNoteAopBypassed() {
        // NOTE: This test instantiates InventoryGrpcClient directly (no Spring context),
        // so @CircuitBreaker and @Retry AOP proxies are NOT active. In production the
        // @CircuitBreaker fallback intercepts transient errors and throws
        // InventoryServiceUnavailableException instead of propagating StatusRuntimeException.
        // The contract verified here (raw StatusRuntimeException propagates) only holds
        // when Spring AOP is absent. See integration tests for the actual runtime contract.
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId("order-1")
                .build();

        when(stub.reserveInventory(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Service down")));

        assertThatThrownBy(() -> client.reserveInventory(request))
                .isInstanceOf(StatusRuntimeException.class);
    }
}
