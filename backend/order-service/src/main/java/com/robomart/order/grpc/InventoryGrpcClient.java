package com.robomart.order.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class InventoryGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcClient.class);
    private static final String INSTANCE = "inventory-service";

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    public InventoryGrpcClient(InventoryServiceGrpc.InventoryServiceBlockingStub stub) {
        this.stub = stub;
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "reserveFallback")
    @Retry(name = INSTANCE)
    public ReserveInventoryResponse reserveInventory(ReserveInventoryRequest request) {
        try {
            return stub.reserveInventory(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "releaseFallback")
    @Retry(name = INSTANCE)
    public ReleaseInventoryResponse releaseInventory(ReleaseInventoryRequest request) {
        try {
            return stub.releaseInventory(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    /**
     * Re-throws deterministic business rejections (FAILED_PRECONDITION) as
     * {@link InventoryBusinessException} so Resilience4j ignores them (no retry, no breaker trip).
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
            return new InventoryBusinessException(e.getStatus().getDescription(), e);
        }
        return e;
    }

    public ReserveInventoryResponse reserveFallback(ReserveInventoryRequest request, Throwable t) {
        // The circuit-breaker fallback is invoked for ALL exceptions, including those marked
        // ignore-exceptions. Business rejections must propagate unchanged — they are NOT a
        // service outage and must not be reported as "temporarily unavailable".
        if (t instanceof InventoryBusinessException businessError) {
            throw businessError;
        }
        log.error("Inventory circuit open or retries exhausted for reserveInventory: {}", t.getMessage());
        throw new InventoryServiceUnavailableException("Inventory service unavailable", t);
    }

    public ReleaseInventoryResponse releaseFallback(ReleaseInventoryRequest request, Throwable t) {
        if (t instanceof InventoryBusinessException businessError) {
            throw businessError;
        }
        log.error("Inventory circuit open or retries exhausted for releaseInventory: {}", t.getMessage());
        throw new InventoryServiceUnavailableException("Inventory service unavailable during release", t);
    }
}
