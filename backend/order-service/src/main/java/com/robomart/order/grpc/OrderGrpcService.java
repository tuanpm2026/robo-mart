package com.robomart.order.grpc;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.saga.exception.SagaStepException;
import com.robomart.order.service.OrderService;
import com.robomart.proto.common.Address;
import com.robomart.proto.common.Money;
import com.robomart.proto.order.CancelOrderRequest;
import com.robomart.proto.order.CancelOrderResponse;
import com.robomart.proto.order.CreateOrderRequest;
import com.robomart.proto.order.CreateOrderResponse;
import com.robomart.proto.order.GetOrderRequest;
import com.robomart.proto.order.GetOrderResponse;
import com.robomart.proto.order.OrderItemResponse;
import com.robomart.proto.order.OrderServiceGrpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@GrpcService
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OrderGrpcService.class);

    private final OrderService orderService;

    public OrderGrpcService(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void createOrder(CreateOrderRequest request, StreamObserver<CreateOrderResponse> responseObserver) {
        try {
            List<OrderService.OrderItemRequest> items = request.getItemsList().stream()
                    .map(item -> new OrderService.OrderItemRequest(
                            item.getProductId(),
                            item.getProductName(),
                            item.getQuantity(),
                            new BigDecimal(item.getUnitPrice().getAmount())))
                    .toList();

            String shippingAddress = formatAddress(request.getShippingAddress());
            Order order = orderService.createOrder(request.getUserId(), items, shippingAddress);

            responseObserver.onNext(CreateOrderResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Order created successfully")
                    .setOrderId(order.getId().toString())
                    .setStatus(order.getStatus().name())
                    .build());
            responseObserver.onCompleted();
        } catch (SagaStepException e) {
            log.error("Saga failed for createOrder: {}", e.getMessage());
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("Unexpected error in createOrder: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> responseObserver) {
        try {
            Long orderId = Long.parseLong(request.getOrderId());
            Order order = orderService.getOrder(orderId);

            GetOrderResponse.Builder responseBuilder = GetOrderResponse.newBuilder()
                    .setOrderId(order.getId().toString())
                    .setUserId(order.getUserId())
                    .setTotalAmount(Money.newBuilder()
                            .setCurrency("USD")
                            .setAmount(order.getTotalAmount().toPlainString())
                            .build())
                    .setStatus(order.getStatus().name())
                    .setCreatedAt(order.getCreatedAt().toEpochMilli())
                    .setUpdatedAt(order.getUpdatedAt().toEpochMilli());

            for (OrderItem item : order.getItems()) {
                responseBuilder.addItems(OrderItemResponse.newBuilder()
                        .setProductId(item.getProductId().toString())
                        .setProductName(item.getProductName())
                        .setQuantity(item.getQuantity())
                        .setUnitPrice(Money.newBuilder()
                                .setCurrency("USD")
                                .setAmount(item.getUnitPrice().toPlainString())
                                .build())
                        .setSubtotal(Money.newBuilder()
                                .setCurrency("USD")
                                .setAmount(item.getSubtotal().toPlainString())
                                .build())
                        .build());
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (ResourceNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (NumberFormatException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid orderId format").asRuntimeException());
        } catch (Exception e) {
            log.error("Unexpected error in getOrder: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("CancelOrder not yet implemented — Story 4.5").asRuntimeException());
    }

    private String formatAddress(Address address) {
        return String.join(", ", address.getStreet(), address.getCity(), address.getState(),
                address.getZip(), address.getCountry());
    }
}
