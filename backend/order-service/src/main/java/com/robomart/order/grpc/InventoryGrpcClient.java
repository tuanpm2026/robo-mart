package com.robomart.order.grpc;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.config.SagaProperties;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.StatusRuntimeException;

@Component
public class InventoryGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcClient.class);
    private static final String INSTANCE = "inventory-service";

    private final SagaProperties sagaProperties;
    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    public InventoryGrpcClient(InventoryServiceGrpc.InventoryServiceBlockingStub stub, SagaProperties sagaProperties) {
        this.stub = stub;
        this.sagaProperties = sagaProperties;
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "reserveFallback")
    @Retry(name = INSTANCE)
    public ReserveInventoryResponse reserveInventory(ReserveInventoryRequest request) {
        try {
            return stub.withDeadlineAfter(sagaProperties.getSteps().timeoutFor("ReserveInventory").toMillis(),
                    TimeUnit.MILLISECONDS).reserveInventory(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "releaseFallback")
    @Retry(name = INSTANCE)
    public ReleaseInventoryResponse releaseInventory(ReleaseInventoryRequest request) {
        try {
            return stub.withDeadlineAfter(sagaProperties.getSteps().timeoutFor("ReleaseInventory").toMillis(),
                    TimeUnit.MILLISECONDS).releaseInventory(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "releaseFallback")
    @Retry(name = INSTANCE)
    public ReleaseInventoryResponse releaseInventoryForRecovery(ReleaseInventoryRequest request) {
        try {
            return stub.withDeadlineAfter(5, TimeUnit.SECONDS).releaseInventory(request);
        } catch (StatusRuntimeException e) {
            throw mapBusinessError(e);
        }
    }

    private static RuntimeException mapBusinessError(StatusRuntimeException e) {
        if (GrpcStatusClassifier.isBusinessError(e.getStatus().getCode())) {
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
