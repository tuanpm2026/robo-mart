package com.robomart.order.saga.steps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.grpc.InventoryBusinessException;
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
        String reservationId = order.getReservationId();

        // Release by orderId — do NOT skip when reservationId is null. On a ReserveInventory
        // step timeout the gRPC reserve may have succeeded server-side while its response (and thus
        // the reservationId) was lost, leaving stock reserved under this orderId. The inventory
        // service dedups release by orderId and is a no-op when nothing is reserved, so issuing the
        // release unconditionally fixes the leak and stays safe when nothing was actually reserved.
        log.info("Releasing inventory for orderId={}, reservationId={}",
                order.getId(), reservationId != null ? reservationId : "<none>");

        ReleaseInventoryRequest.Builder requestBuilder = ReleaseInventoryRequest.newBuilder()
                .setOrderId(order.getId().toString());
        if (reservationId != null) {
            requestBuilder.setReservationId(reservationId);
        }

        for (OrderItem item : order.getItems()) {
            requestBuilder.addItems(ReservationItem.newBuilder()
                    .setProductId(item.getProductId().toString())
                    .setQuantity(item.getQuantity())
                    .build());
        }

        try {
            inventoryClient.releaseInventory(requestBuilder.build());
            log.info("Inventory released for orderId={}", order.getId());
        } catch (StatusRuntimeException | InventoryServiceUnavailableException | InventoryBusinessException e) {
            // Best-effort / idempotent — release failures (transient, circuit-open, or a business
            // rejection such as FAILED_PRECONDITION when the reservation was already released) are
            // logged but never re-thrown, so the saga can continue cancelling.
            log.error("Failed to release inventory for orderId={}: {}", order.getId(), e.getMessage(), e);
        }
    }
}
