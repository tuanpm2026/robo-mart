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
        return stub.reserveInventory(request);
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "releaseFallback")
    @Retry(name = INSTANCE)
    public ReleaseInventoryResponse releaseInventory(ReleaseInventoryRequest request) {
        return stub.releaseInventory(request);
    }

    public ReserveInventoryResponse reserveFallback(ReserveInventoryRequest request, Throwable t) {
        log.error("Inventory circuit open or retries exhausted for reserveInventory: {}", t.getMessage());
        throw new InventoryServiceUnavailableException("Inventory service unavailable", t);
    }

    public ReleaseInventoryResponse releaseFallback(ReleaseInventoryRequest request, Throwable t) {
        log.error("Inventory circuit open or retries exhausted for releaseInventory: {}", t.getMessage());
        throw new InventoryServiceUnavailableException("Inventory service unavailable during release", t);
    }
}
