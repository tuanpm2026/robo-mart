package com.robomart.order.saga.steps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.grpc.InventoryGrpcClient;
import com.robomart.order.grpc.InventoryServiceUnavailableException;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.SagaStep;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReservationItem;

import io.grpc.StatusRuntimeException;

@Component
public class ReleaseInventoryStep implements SagaStep {

    private static final Logger log = LoggerFactory.getLogger(ReleaseInventoryStep.class);

    private final InventoryGrpcClient inventoryClient;

    public ReleaseInventoryStep(InventoryGrpcClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @Override
    public String getName() {
        return "ReleaseInventory";
    }

    @Override
    public void execute(SagaContext context) {
        throw new UnsupportedOperationException("ReleaseInventoryStep is a compensation-only step");
    }

    @Override
    public void compensate(SagaContext context) {
        Order order = context.getOrder();

        if (order.getReservationId() == null) {
            log.debug("ReleaseInventoryStep.compensate() skipped — no reservationId for orderId={}", order.getId());
            return;
        }

        log.info("Releasing inventory for orderId={}, reservationId={}", order.getId(), order.getReservationId());

        ReleaseInventoryRequest.Builder requestBuilder = ReleaseInventoryRequest.newBuilder()
                .setOrderId(order.getId().toString())
                .setReservationId(order.getReservationId());

        for (OrderItem item : order.getItems()) {
            requestBuilder.addItems(ReservationItem.newBuilder()
                    .setProductId(item.getProductId().toString())
                    .setQuantity(item.getQuantity())
                    .build());
        }

        try {
            inventoryClient.releaseInventory(requestBuilder.build());
            log.info("Inventory released for orderId={}", order.getId());
        } catch (StatusRuntimeException | InventoryServiceUnavailableException e) {
            log.error("Failed to release inventory for orderId={}: {}", order.getId(), e.getMessage(), e);
            // Best-effort — log but do not throw, to allow saga to continue cancelling
        }
    }
}
