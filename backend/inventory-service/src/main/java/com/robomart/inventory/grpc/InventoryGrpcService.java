package com.robomart.inventory.grpc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.grpc.server.service.GrpcService;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.entity.Reservation;
import com.robomart.inventory.enums.ReservationStatus;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.exception.LockAcquisitionException;
import com.robomart.inventory.exception.ReservationReleasedException;
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

        try {
            // Fast path for retries/replays: answer from the existing reservation without taking any
            // locks. reserveOrder re-checks atomically under the order_id unique key.
            Optional<Reservation> existing = inventoryService.findReservation(orderId);
            if (existing.isPresent()) {
                Reservation reservation = existing.get();
                if (reservation.getStatus() == ReservationStatus.RELEASED) {
                    throw new ReservationReleasedException(orderId);
                }
                log.info("reserveInventory idempotent replay: returning existing reservationId={} for orderId={}",
                        reservation.getReservationId(), orderId);
                respondReserved(responseObserver, reservation.getReservationId(), true);
                return;
            }

            // Validate the whole request before touching stock. Duplicate product lines are merged.
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            for (ReservationItem item : request.getItemsList()) {
                Long productId = parseProductId(item.getProductId());
                int quantity = item.getQuantity();
                if (quantity <= 0) {
                    throw new IllegalArgumentException(
                            "Quantity must be positive for product " + item.getProductId() + ", got: " + quantity);
                }
                quantities.merge(productId, quantity, Math::addExact);
            }

            // Reservation row, lines and all stock decrements commit in one transaction: a failure on
            // any item rolls everything back, so there is nothing to compensate here.
            InventoryService.ReservationResult result = inventoryService.reserveOrder(orderId, quantities);
            if (result.replayed()) {
                log.info("reserveInventory idempotent replay: returning existing reservationId={} for orderId={}",
                        result.reservationId(), orderId);
            } else {
                log.info("All items reserved successfully: orderId={}, reservationId={}, itemCount={}",
                        orderId, result.reservationId(), quantities.size());
            }
            respondReserved(responseObserver, result.reservationId(), result.replayed());

        } catch (NumberFormatException e) {
            log.warn("Invalid product_id format in reserveInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid product_id format: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (IllegalArgumentException | ArithmeticException e) {
            log.warn("Invalid argument in reserveInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (ReservationReleasedException e) {
            log.warn("reserveInventory rejected: reservation already released for orderId={}", orderId);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock in reserveInventory: orderId={}, productId={}", orderId, e.getProductId(), e);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (LockAcquisitionException e) {
            log.warn("Lock acquisition failed in reserveInventory: orderId={}, productId={}", orderId, e.getProductId(), e);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ResourceNotFoundException e) {
            log.warn("Resource not found in reserveInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ConcurrencyFailureException e) {
            log.warn("Concurrency failure in reserveInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.ABORTED
                    .withDescription("Concurrent modification detected, please retry")
                    .asRuntimeException());

        } catch (IllegalStateException e) {
            log.warn("Illegal state in reserveInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (Exception e) {
            log.error("Unexpected error in reserveInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during inventory reservation")
                    .asRuntimeException());
        }
    }

    /**
     * Releases the order's reservation. Stock is restored ONLY from the reservation lines stored at
     * reserve time; the request's items are ignored (they are informational and untrusted). Releasing
     * an order that has no reservation records a RELEASED tombstone and restores nothing, so it is
     * safe to call after a failed or timed-out reserve, and it also blocks a late reserve for that order.
     */
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

        try {
            InventoryService.ReleaseOutcome outcome = inventoryService.releaseReservation(orderId);
            String message;
            switch (outcome) {
                case RELEASED:
                    message = "Inventory released successfully";
                    break;
                case ALREADY_RELEASED:
                    message = "Inventory already released";
                    break;
                default:
                    message = "No reservation found; nothing to release";
                    break;
            }
            log.info("releaseInventory completed: orderId={}, reservationId={}, outcome={}",
                    orderId, reservationId, outcome);

            responseObserver.onNext(ReleaseInventoryResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage(message)
                    .build());
            responseObserver.onCompleted();

        } catch (ResourceNotFoundException e) {
            log.warn("Resource not found in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());

        } catch (ConcurrencyFailureException e) {
            log.warn("Concurrency failure in releaseInventory: orderId={}", orderId, e);
            responseObserver.onError(Status.ABORTED
                    .withDescription("Concurrent modification detected, please retry")
                    .asRuntimeException());

        } catch (IllegalStateException e) {
            log.error("Illegal state in releaseInventory: orderId={} — reservation left RESERVED", orderId, e);
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

    private void respondReserved(StreamObserver<ReserveInventoryResponse> responseObserver,
                                 String reservationId, boolean replayed) {
        responseObserver.onNext(ReserveInventoryResponse.newBuilder()
                .setSuccess(true)
                .setMessage(replayed ? "Inventory already reserved" : "Inventory reserved successfully")
                .setReservationId(reservationId)
                .build());
        responseObserver.onCompleted();
    }
}
