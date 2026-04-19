package com.robomart.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import com.robomart.proto.common.Money;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReservationItem;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.order.OrderServiceGrpc;

/**
 * Proto schema validation tests: verify all generated gRPC stubs are loadable
 * and their proto-generated builder/descriptor interfaces are accessible.
 *
 * These tests catch issues with:
 * - Missing generated stubs (protobuf-maven-plugin not run)
 * - Classpath/dependency problems
 * - Incompatible protobuf versions
 *
 * For backward-compatibility lint (no removed/renamed fields), use buf lint:
 *   cd backend/proto && buf lint
 * Enable in Maven with: mvn verify -DskipBufLint=false
 */
class ProtoSchemaValidationTest {

    @Test
    void shouldLoadInventoryServiceStubs() {
        assertThatCode(() -> {
            io.grpc.Channel channel = io.grpc.ManagedChannelBuilder
                    .forAddress("localhost", 9090)
                    .usePlaintext()
                    .build();
            InventoryServiceGrpc.InventoryServiceBlockingStub stub =
                    InventoryServiceGrpc.newBlockingStub(channel);
            assertThat(stub).isNotNull();
            ((io.grpc.ManagedChannel) channel).shutdownNow();
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldLoadPaymentServiceStubs() {
        assertThatCode(() -> {
            io.grpc.Channel channel = io.grpc.ManagedChannelBuilder
                    .forAddress("localhost", 9090)
                    .usePlaintext()
                    .build();
            PaymentServiceGrpc.PaymentServiceBlockingStub stub =
                    PaymentServiceGrpc.newBlockingStub(channel);
            assertThat(stub).isNotNull();
            ((io.grpc.ManagedChannel) channel).shutdownNow();
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldLoadOrderServiceStubs() {
        assertThatCode(() -> {
            io.grpc.Channel channel = io.grpc.ManagedChannelBuilder
                    .forAddress("localhost", 9090)
                    .usePlaintext()
                    .build();
            OrderServiceGrpc.OrderServiceBlockingStub stub =
                    OrderServiceGrpc.newBlockingStub(channel);
            assertThat(stub).isNotNull();
            ((io.grpc.ManagedChannel) channel).shutdownNow();
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldBuildReserveInventoryRequest() {
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                .setOrderId("order-001")
                .addItems(ReservationItem.newBuilder()
                        .setProductId("prod-001")
                        .setQuantity(1)
                        .build())
                .build();

        assertThat(request.getOrderId()).isEqualTo("order-001");
        assertThat(request.getItemsCount()).isEqualTo(1);
        assertThat(request.getItems(0).getProductId()).isEqualTo("prod-001");
    }

    @Test
    void shouldBuildReleaseInventoryRequest() {
        ReleaseInventoryRequest request = ReleaseInventoryRequest.newBuilder()
                .setOrderId("order-001")
                .setReservationId("res-001")
                .build();

        assertThat(request.getOrderId()).isEqualTo("order-001");
        assertThat(request.getReservationId()).isEqualTo("res-001");
    }

    @Test
    void shouldBuildProcessPaymentRequest() {
        Money amount = Money.newBuilder()
                .setCurrency("USD")
                .setAmount("99.99")
                .build();
        ProcessPaymentRequest request = ProcessPaymentRequest.newBuilder()
                .setOrderId("order-001")
                .setUserId("user-001")
                .setAmount(amount)
                .setIdempotencyKey("idem-001")
                .build();

        assertThat(request.getOrderId()).isEqualTo("order-001");
        assertThat(request.getAmount().getAmount()).isEqualTo("99.99");
    }

    @Test
    void shouldBuildRefundPaymentRequest() {
        RefundPaymentRequest request = RefundPaymentRequest.newBuilder()
                .setPaymentId("pay-001")
                .setOrderId("order-001")
                .setReason("Cancelled by customer")
                .build();

        assertThat(request.getPaymentId()).isEqualTo("pay-001");
        assertThat(request.getReason()).isEqualTo("Cancelled by customer");
    }

    @Test
    void shouldHaveCorrectInventoryResponseFields() {
        ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                .setSuccess(true)
                .setReservationId("res-test-001")
                .build();

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getReservationId()).isEqualTo("res-test-001");
    }
}
