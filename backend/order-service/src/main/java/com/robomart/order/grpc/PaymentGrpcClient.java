package com.robomart.order.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@Component
public class PaymentGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcClient.class);
    private static final String INSTANCE = "payment-service";

    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    public PaymentGrpcClient(PaymentServiceGrpc.PaymentServiceBlockingStub stub) {
        this.stub = stub;
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "paymentFallback")
    @Retry(name = INSTANCE)
    public ProcessPaymentResponse processPayment(ProcessPaymentRequest request) {
        return stub.processPayment(request);
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "refundFallback")
    @Retry(name = INSTANCE)
    public RefundPaymentResponse refundPayment(RefundPaymentRequest request) {
        return stub.refundPayment(request);
    }

    public ProcessPaymentResponse paymentFallback(ProcessPaymentRequest request, Throwable t) {
        log.error("Payment circuit open or retries exhausted for processPayment: {}", t.getMessage());
        throw new PaymentServiceUnavailableException("Payment service unavailable", t);
    }

    public RefundPaymentResponse refundFallback(RefundPaymentRequest request, Throwable t) {
        log.error("Payment circuit open or retries exhausted for refundPayment: {}", t.getMessage());
        throw new PaymentServiceUnavailableException("Payment service unavailable during refund", t);
    }
}
