package com.robomart.order.grpc;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.config.SagaProperties;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.proto.payment.RefundPaymentRequest;
import com.robomart.proto.payment.RefundPaymentResponse;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.resilience.annotation.Retryable;
import io.grpc.StatusRuntimeException;

@Component
public class PaymentGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcClient.class);
    private static final String INSTANCE = "payment-service";

    private final SagaProperties sagaProperties;
    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    public PaymentGrpcClient(PaymentServiceGrpc.PaymentServiceBlockingStub stub, SagaProperties sagaProperties) {
        this.stub = stub;
        this.sagaProperties = sagaProperties;
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "paymentFallback")
    @Retryable(predicate = GrpcStatusClassifier.class, maxRetries = 2,
            delayString = "${grpc.retry.initial-delay-ms:1000}", multiplier = 2, maxDelay = 2000)
    public ProcessPaymentResponse processPayment(ProcessPaymentRequest request) {
        try {
            return stub.withDeadlineAfter(sagaProperties.getSteps().timeoutFor("ProcessPayment").toMillis(),
                    TimeUnit.MILLISECONDS).processPayment(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "paymentFallback")
    @Retryable(predicate = GrpcStatusClassifier.class, maxRetries = 2,
            delayString = "${grpc.retry.initial-delay-ms:1000}", multiplier = 2, maxDelay = 2000)
    public ProcessPaymentResponse resumePayment(ProcessPaymentRequest request) {
        try {
            return stub.withDeadlineAfter(5, TimeUnit.SECONDS).processPayment(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "refundFallback")
    @Retryable(predicate = GrpcStatusClassifier.class, maxRetries = 2,
            delayString = "${grpc.retry.initial-delay-ms:1000}", multiplier = 2, maxDelay = 2000)
    public RefundPaymentResponse refundPayment(RefundPaymentRequest request) {
        try {
            return stub.withDeadlineAfter(sagaProperties.getSteps().timeoutFor("RefundPaymentStep").toMillis(),
                    TimeUnit.MILLISECONDS).refundPayment(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    private static RuntimeException mapBusinessError(StatusRuntimeException e) {
        if (GrpcStatusClassifier.isBusinessError(e.getStatus().getCode())) {
            return new PaymentBusinessException(e.getStatus().getDescription(), e);
        }
        return e;
    }

    public ProcessPaymentResponse paymentFallback(ProcessPaymentRequest request, Throwable t) {
        // The circuit-breaker fallback is invoked for ALL exceptions, including those marked
        // ignore-exceptions. Business rejections (payment declined) must propagate unchanged —
        // they are NOT a service outage and must not be held as PAYMENT_PENDING.
        if (t instanceof PaymentBusinessException businessError) {
            throw businessError;
        }
        log.error("Payment circuit open or retries exhausted for processPayment: {}", t.getMessage());
        throw new PaymentServiceUnavailableException("Payment service unavailable", t);
    }

    public RefundPaymentResponse refundFallback(RefundPaymentRequest request, Throwable t) {
        if (t instanceof PaymentBusinessException businessError) {
            throw businessError;
        }
        log.error("Payment circuit open or retries exhausted for refundPayment: {}", t.getMessage());
        throw new PaymentServiceUnavailableException("Payment service unavailable during refund", t);
    }
}
