package com.robomart.payment.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.TestPropertySource;

import com.robomart.payment.repository.IdempotencyKeyRepository;
import com.robomart.payment.repository.OutboxEventRepository;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.service.MockPaymentGateway;
import com.robomart.proto.common.Money;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;
import com.robomart.test.IntegrationTest;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@IntegrationTest
@TestPropertySource(properties = "spring.grpc.server.port=0")
class PaymentGrpcIT {

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private MockPaymentGateway mockPaymentGateway;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private ManagedChannel channel;
    private PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        int grpcPort = grpcServerLifecycle.getPort();
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        stub = PaymentServiceGrpc.newBlockingStub(channel);

        mockPaymentGateway.reset();
        outboxEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
        }
    }

    @Test
    void shouldProcessPaymentViaGrpc() {
        // Arrange
        ProcessPaymentRequest request = ProcessPaymentRequest.newBuilder()
                .setOrderId("grpc-order-1")
                .setUserId("user-1")
                .setAmount(Money.newBuilder().setAmount("99.99").setCurrency("USD").build())
                .setIdempotencyKey("grpc-idem-1")
                .build();

        // Act
        ProcessPaymentResponse response = stub.processPayment(request);

        // Assert
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getPaymentId()).isNotEmpty();
        assertThat(response.getTransactionId()).startsWith("txn-");
    }

    @Test
    void shouldReturnSameResultForDuplicateIdempotencyKey() {
        // Arrange
        ProcessPaymentRequest request = ProcessPaymentRequest.newBuilder()
                .setOrderId("grpc-order-2")
                .setUserId("user-1")
                .setAmount(Money.newBuilder().setAmount("50.00").setCurrency("USD").build())
                .setIdempotencyKey("grpc-idem-dup")
                .build();

        // Act
        ProcessPaymentResponse first = stub.processPayment(request);
        ProcessPaymentResponse second = stub.processPayment(request);

        // Assert: same result
        assertThat(second.getPaymentId()).isEqualTo(first.getPaymentId());
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
    }

    @Test
    void shouldRefundPaymentViaGrpc() {
        // Arrange: process a payment first
        ProcessPaymentRequest processRequest = ProcessPaymentRequest.newBuilder()
                .setOrderId("grpc-order-3")
                .setUserId("user-1")
                .setAmount(Money.newBuilder().setAmount("75.00").setCurrency("USD").build())
                .setIdempotencyKey("grpc-idem-refund")
                .build();
        ProcessPaymentResponse processResponse = stub.processPayment(processRequest);

        // Act: refund
        RefundPaymentRequest refundRequest = RefundPaymentRequest.newBuilder()
                .setPaymentId(processResponse.getPaymentId())
                .setOrderId("grpc-order-3")
                .setAmount(Money.newBuilder().setAmount("75.00").setCurrency("USD").build())
                .setReason("Customer request")
                .build();
        RefundPaymentResponse refundResponse = stub.refundPayment(refundRequest);

        // Assert
        assertThat(refundResponse.getSuccess()).isTrue();
        assertThat(refundResponse.getRefundTransactionId()).startsWith("refund-");
    }

    @Test
    void shouldReturnUnavailableOnTransientFailure() {
        // Arrange
        mockPaymentGateway.setSimulateTransientFailure(true);
        ProcessPaymentRequest request = ProcessPaymentRequest.newBuilder()
                .setOrderId("grpc-order-transient")
                .setUserId("user-1")
                .setAmount(Money.newBuilder().setAmount("10.00").setCurrency("USD").build())
                .setIdempotencyKey("grpc-idem-transient")
                .build();

        // Act & Assert
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.processPayment(request));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void shouldReturnFailedPreconditionOnDeclined() {
        // Arrange
        mockPaymentGateway.setSimulatePermanentFailure(true);
        ProcessPaymentRequest request = ProcessPaymentRequest.newBuilder()
                .setOrderId("grpc-order-declined")
                .setUserId("user-1")
                .setAmount(Money.newBuilder().setAmount("10.00").setCurrency("USD").build())
                .setIdempotencyKey("grpc-idem-declined")
                .build();

        // Act & Assert
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.processPayment(request));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void shouldReturnNotFoundWhenRefundingNonExistentPayment() {
        // Arrange
        RefundPaymentRequest request = RefundPaymentRequest.newBuilder()
                .setPaymentId("999999")
                .setOrderId("grpc-order-notfound")
                .setAmount(Money.newBuilder().setAmount("10.00").setCurrency("USD").build())
                .setReason("test")
                .build();

        // Act & Assert
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.refundPayment(request));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void shouldReturnInvalidArgumentWhenOrderIdEmpty() {
        // Arrange
        ProcessPaymentRequest request = ProcessPaymentRequest.newBuilder()
                .setOrderId("")
                .setUserId("user-1")
                .setAmount(Money.newBuilder().setAmount("10.00").setCurrency("USD").build())
                .setIdempotencyKey("key-1")
                .build();

        // Act & Assert
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.processPayment(request));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
}
