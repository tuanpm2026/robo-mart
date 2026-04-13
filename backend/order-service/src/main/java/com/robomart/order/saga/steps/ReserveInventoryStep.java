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
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.proto.inventory.ReservationItem;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class ReserveInventoryStep implements SagaStep {

    private static final Logger log = LoggerFactory.getLogger(ReserveInventoryStep.class);

    private final InventoryGrpcClient inventoryClient;

    public ReserveInventoryStep(InventoryGrpcClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @Override
    public String getName() {
        return "ReserveInventory";
    }

    @Override
    public void execute(SagaContext context) {
        Order order = context.getOrder();
        log.info("Reserving inventory for orderId={}", order.getId());

        ReserveInventoryRequest.Builder requestBuilder = ReserveInventoryRequest.newBuilder()
                .setOrderId(order.getId().toString());

        for (OrderItem item : order.getItems()) {
            requestBuilder.addItems(ReservationItem.newBuilder()
                    .setProductId(item.getProductId().toString())
                    .setQuantity(item.getQuantity())
                    .build());
        }

        try {
            ReserveInventoryResponse response = inventoryClient.reserveInventory(requestBuilder.build());
            order.setReservationId(response.getReservationId());
            log.info("Inventory reserved for orderId={}, reservationId={}", order.getId(), response.getReservationId());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                order.setCancellationReason("Insufficient stock");
                throw new SagaStepException("Insufficient stock for orderId=" + order.getId(), e, false);
            }
            throw new SagaStepException("Inventory reservation failed for orderId=" + order.getId(), e, true);
        } catch (InventoryServiceUnavailableException e) {
            throw new SagaStepException("Inventory service circuit open for orderId=" + order.getId(), e, true);
        }
    }

    @Override
    public void compensate(SagaContext context) {
        // No-op: if execute() failed, inventory was not reserved
        log.debug("ReserveInventoryStep.compensate() called — no-op (nothing reserved)");
    }
}
