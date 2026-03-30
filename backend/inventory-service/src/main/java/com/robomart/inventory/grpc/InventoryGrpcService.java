package com.robomart.inventory.grpc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.exception.LockAcquisitionException;
import com.robomart.inventory.service.InventoryService;
import com.robomart.proto.inventory.GetInventoryRequest;
import com.robomart.proto.inventory.GetInventoryResponse;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReservationItem;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@GrpcService
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcService.class);

    private final InventoryService inventoryService;

    public InventoryGrpcService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public void reserveInventory(ReserveInventoryRequest request,
                                 StreamObserver<ReserveInventoryResponse> responseObserver) {
        String orderId = request.getOrderId();
        log.info("gRPC reserveInventory called: orderId={}, itemCount={}", orderId, request.getItemsCount());

        if (orderId.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("order_id is required")
                    .asRuntimeException());
            return;
        }
        if (request.getItemsCount() == 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("At least one item is required")
                    .asRuntimeException());
            return;
        }

        // Track successfully reserved items for rollback on partial failure
        List<ReservationItem> reservedItems = new ArrayList<>();

        try {
            for (ReservationItem item : request.getItemsList()) {
                Long productId = parseProductId(item.getProductId());
                int quantity = item.getQuantity();
                if (quantity <= 0) {
                    throw new IllegalArgumentException("Quantity must be positive for product " + item.getProductId() + ", got: " + quantity);
                }

                log.debug("Reserving stock: productId={}, quantity={}, orderId={}", productId, quantity, orderId);
                inventoryService.reserveStock(productId, quantity, orderId);
                reservedItems.add(item);
            }

            String reservationId = UUID.randomUUID().toString();
            log.info("All items reserved successfully: orderId={}, reservationId={}, itemCount={}",
                    orderId, reservationId, reservedItems.size());

            ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Inventory reserved successfully")
                    .setReservationId(reservationId)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (NumberFormatException e) {
            log.warn("Invalid product_id format in reserveInventory: orderId={}", orderId, e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid product_id format: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (IllegalArgumentException e) {
            log.warn("Invalid argument in reserveInventory: orderId={}", orderId, e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock in reserveInventory: orderId={}, productId={}", orderId, e.getProductId(), e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (LockAcquisitionException e) {
            log.warn("Lock acquisition failed in reserveInventory: orderId={}, productId={}", orderId, e.getProductId(), e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ResourceNotFoundException e) {
            log.warn("Resource not found in reserveInventory: orderId={}", orderId, e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock failure in reserveInventory: orderId={}", orderId, e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.ABORTED
                    .withDescription("Concurrent modification detected, please retry")
                    .asRuntimeException());

        } catch (IllegalStateException e) {
            log.warn("Illegal state in reserveInventory: orderId={}", orderId, e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (Exception e) {
            log.error("Unexpected error in reserveInventory: orderId={}", orderId, e);
            rollbackReservations(reservedItems, orderId);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during inventory reservation")
                    .asRuntimeException());
        }
    }

    @Override
    public void releaseInventory(ReleaseInventoryRequest request,
                                 StreamObserver<ReleaseInventoryResponse> responseObserver) {
        String orderId = request.getOrderId();
        String reservationId = request.getReservationId();
        log.info("gRPC releaseInventory called: orderId={}, reservationId={}, itemCount={}",
                orderId, reservationId, request.getItemsCount());

        if (orderId.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("order_id is required")
                    .asRuntimeException());
            return;
        }
        if (request.getItemsCount() == 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("At least one item is required")
                    .asRuntimeException());
            return;
        }

        try {
            for (ReservationItem item : request.getItemsList()) {
                Long productId = parseProductId(item.getProductId());
                int quantity = item.getQuantity();
                if (quantity <= 0) {
                    throw new IllegalArgumentException("Quantity must be positive for product " + item.getProductId() + ", got: " + quantity);
                }

                log.debug("Releasing stock: productId={}, quantity={}, orderId={}", productId, quantity, orderId);
                inventoryService.releaseStock(productId, quantity, orderId);
            }

            log.info("All items released successfully: orderId={}, reservationId={}", orderId, reservationId);

            ReleaseInventoryResponse response = ReleaseInventoryResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Inventory released successfully")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (NumberFormatException e) {
            log.warn("Invalid product_id format in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid product_id format: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (IllegalArgumentException e) {
            log.warn("Invalid argument in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (LockAcquisitionException e) {
            log.warn("Lock acquisition failed in releaseInventory: orderId={}, productId={}", orderId, e.getProductId(), e);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ResourceNotFoundException e) {
            log.warn("Resource not found in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock failure in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.ABORTED
                    .withDescription("Concurrent modification detected, please retry")
                    .asRuntimeException());

        } catch (IllegalStateException e) {
            log.warn("Illegal state in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (Exception e) {
            log.error("Unexpected error in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during inventory release")
                    .asRuntimeException());
        }
    }

    @Override
    public void getInventory(GetInventoryRequest request,
                             StreamObserver<GetInventoryResponse> responseObserver) {
        String productIdStr = request.getProductId();
        log.info("gRPC getInventory called: productId={}", productIdStr);

        try {
            Long productId = parseProductId(productIdStr);
            InventoryItem item = inventoryService.getInventory(productId);

            log.debug("Inventory retrieved: productId={}, available={}, reserved={}, total={}",
                    productId, item.getAvailableQuantity(), item.getReservedQuantity(), item.getTotalQuantity());

            GetInventoryResponse response = GetInventoryResponse.newBuilder()
                    .setProductId(productIdStr)
                    .setAvailableQuantity(item.getAvailableQuantity())
                    .setReservedQuantity(item.getReservedQuantity())
                    .setTotalQuantity(item.getTotalQuantity())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (NumberFormatException e) {
            log.warn("Invalid product_id format in getInventory: productId={}", productIdStr, e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid product_id format: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ResourceNotFoundException e) {
            log.warn("Resource not found in getInventory: productId={}", productIdStr, e);
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (Exception e) {
            log.error("Unexpected error in getInventory: productId={}", productIdStr, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during inventory retrieval")
                    .asRuntimeException());
        }
    }

    /**
     * Parses a product_id string to Long.
     *
     * @param productId the product_id string from the gRPC request
     * @return the parsed Long value
     * @throws NumberFormatException if the string is not a valid Long
     */
    private Long parseProductId(String productId) {
        return Long.parseLong(productId);
    }

    /**
     * Best-effort rollback of already-reserved items when a subsequent reservation fails.
     * Each release is attempted independently; failures are logged but do not propagate,
     * since this is a compensating action.
     *
     * @param reservedItems the list of ReservationItems that were successfully reserved
     * @param orderId       the order ID used for the original reservation
     */
    private void rollbackReservations(List<ReservationItem> reservedItems, String orderId) {
        if (reservedItems.isEmpty()) {
            return;
        }
        log.warn("Rolling back {} previously reserved items for orderId={}", reservedItems.size(), orderId);
        for (ReservationItem item : reservedItems) {
            try {
                Long productId = Long.parseLong(item.getProductId());
                int quantity = item.getQuantity();
                log.warn("Rollback: releasing productId={}, quantity={}, orderId={}", productId, quantity, orderId);
                inventoryService.releaseStock(productId, quantity, orderId);
            } catch (Exception e) {
                log.error("Failed to rollback reservation for productId={}, orderId={}: manual intervention may be required",
                        item.getProductId(), orderId, e);
            }
        }
    }
}
