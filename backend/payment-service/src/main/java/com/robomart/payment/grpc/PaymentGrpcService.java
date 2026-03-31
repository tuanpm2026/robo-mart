package com.robomart.payment.grpc;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.exception.PaymentDeclinedException;
import com.robomart.payment.exception.PaymentTransientException;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.service.PaymentService.PaymentResult;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@GrpcService
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcService.class);

    private final PaymentService paymentService;

    public PaymentGrpcService(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Override
    public void processPayment(ProcessPaymentRequest request,
                               StreamObserver<ProcessPaymentResponse> responseObserver) {
        String orderId = request.getOrderId();
        String idempotencyKey = request.getIdempotencyKey();
        log.info("gRPC processPayment called: orderId={}, idempotencyKey={}", orderId, idempotencyKey);

        if (orderId.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("order_id is required")
                    .asRuntimeException());
            return;
        }
        if (idempotencyKey.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("idempotency_key is required")
                    .asRuntimeException());
            return;
        }

        try {
            BigDecimal amount = new BigDecimal(request.getAmount().getAmount());
            String currency = request.getAmount().getCurrency();

            PaymentResult result = paymentService.processPayment(
                    orderId, request.getUserId(), amount, currency, idempotencyKey);

            ProcessPaymentResponse response = ProcessPaymentResponse.newBuilder()
                    .setSuccess(result.success())
                    .setMessage(result.message())
                    .setPaymentId(result.paymentId() != null ? result.paymentId() : "")
                    .setTransactionId(result.transactionId() != null ? result.transactionId() : "")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (PaymentDeclinedException e) {
            log.warn("Payment declined: orderId={}", orderId, e);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (PaymentTransientException e) {
            log.warn("Transient payment failure: orderId={}", orderId, e);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (IllegalArgumentException e) {
            log.warn("Invalid argument in processPayment: orderId={}", orderId, e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (Exception e) {
            log.error("Unexpected error in processPayment: orderId={}", orderId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during payment processing")
                    .asRuntimeException());
        }
    }

    @Override
    public void refundPayment(RefundPaymentRequest request,
                              StreamObserver<RefundPaymentResponse> responseObserver) {
        String paymentIdStr = request.getPaymentId();
        String orderId = request.getOrderId();
        log.info("gRPC refundPayment called: paymentId={}, orderId={}", paymentIdStr, orderId);

        if (paymentIdStr.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("payment_id is required")
                    .asRuntimeException());
            return;
        }

        try {
            Long paymentId = Long.parseLong(paymentIdStr);
            BigDecimal amount = new BigDecimal(request.getAmount().getAmount());

            PaymentResult result = paymentService.refundPayment(
                    paymentId, orderId, amount, request.getReason());

            RefundPaymentResponse response = RefundPaymentResponse.newBuilder()
                    .setSuccess(result.success())
                    .setMessage(result.message())
                    .setRefundTransactionId(result.transactionId() != null ? result.transactionId() : "")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (NumberFormatException e) {
            log.warn("Invalid payment_id format: {}", paymentIdStr, e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid payment_id format: " + e.getMessage())
                    .asRuntimeException());

        } catch (ResourceNotFoundException e) {
            log.warn("Payment not found: paymentId={}", paymentIdStr, e);
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (IllegalStateException e) {
            log.warn("Invalid payment state for refund: paymentId={}", paymentIdStr, e);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (PaymentTransientException e) {
            log.warn("Transient failure during refund: paymentId={}", paymentIdStr, e);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (PaymentDeclinedException e) {
            log.warn("Refund declined: paymentId={}", paymentIdStr, e);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (Exception e) {
            log.error("Unexpected error in refundPayment: paymentId={}", paymentIdStr, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during refund processing")
                    .asRuntimeException());
        }
    }
}
