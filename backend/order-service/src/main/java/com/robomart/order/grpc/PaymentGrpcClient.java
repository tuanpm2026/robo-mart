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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

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
        try {
            return stub.processPayment(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "refundFallback")
    @Retry(name = INSTANCE)
    public RefundPaymentResponse refundPayment(RefundPaymentRequest request) {
        try {
            return stub.refundPayment(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    /**
     * Re-throws deterministic business rejections (FAILED_PRECONDITION) as
     * {@link PaymentBusinessException} so Resilience4j ignores them (no retry, no breaker trip).
     *
     * <p>Transient gRPC errors (UNAVAILABLE, DEADLINE_EXCEEDED) are re-thrown unchanged. NOTE: the
     * {@code @Retry} config does NOT currently retry them — Retry is the outer aspect and
     * CircuitBreaker the inner one, and both share this method's {@code fallbackMethod}. The
     * fallback (which catches Throwable) fires on the first failure, before Retry ever observes it,
     * so {@code @Retry} is effectively a no-op for transient gRPC errors here. The circuit breaker
     * still records and trips on those transient errors.
     */
    private static RuntimeException mapBusinessError(StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
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
