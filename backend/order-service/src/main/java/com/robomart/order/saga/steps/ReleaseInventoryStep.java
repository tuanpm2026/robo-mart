package com.robomart.order.saga.steps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.grpc.InventoryGrpcClient;
import com.robomart.order.saga.SagaContext;
import com.robomart.order.saga.SagaStep;
import com.robomart.order.saga.SagaStepResult;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReservationItem;

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

    /** Recovery cannot finalize a declined order until its reservation is actually released. */
    public void releaseForRecovery(Order order) {
        var request = ReleaseInventoryRequest.newBuilder().setOrderId(order.getId().toString());
        if (order.getReservationId() != null) {
            request.setReservationId(order.getReservationId());
        }
        for (OrderItem item : order.getItems()) {
            request.addItems(ReservationItem.newBuilder().setProductId(item.getProductId().toString())
                    .setQuantity(item.getQuantity()));
        }
        var response = inventoryClient.releaseInventoryForRecovery(request.build());
        if (!response.getSuccess()) {
            throw new IllegalStateException("Inventory release was not acknowledged for order " + order.getId());
        }
    }

    @Override
    public SagaStepResult execute(SagaContext context) {
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

        var response = inventoryClient.releaseInventory(requestBuilder.build());
        if (!response.getSuccess()) {
            throw new IllegalStateException("Inventory release was not acknowledged for order " + order.getId());
        }
        log.info("Inventory released for orderId={}", order.getId());
    }
}
