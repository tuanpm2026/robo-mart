package com.robomart.order.unit.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderNotCancellableException;
import com.robomart.order.grpc.OrderGrpcService;
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
import com.robomart.proto.order.OrderItemRequest;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderGrpcService Unit Tests")
class OrderGrpcServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private StreamObserver<CreateOrderResponse> createObserver;

    @Mock
    private StreamObserver<GetOrderResponse> getObserver;

    @Mock
    private StreamObserver<CancelOrderResponse> cancelObserver;

    @Captor
    private ArgumentCaptor<Throwable> errorCaptor;

    @Captor
    private ArgumentCaptor<CreateOrderResponse> createResponseCaptor;

    @Captor
    private ArgumentCaptor<GetOrderResponse> getResponseCaptor;

    @Captor
    private ArgumentCaptor<CancelOrderResponse> cancelResponseCaptor;

    private OrderGrpcService grpcService;

    @BeforeEach
    void setUp() {
        grpcService = new OrderGrpcService(orderService);
    }

    private Order buildOrder(String userId, OrderStatus status) {
        Order order = new Order();
        try {
            Class<?> base = com.robomart.common.entity.BaseEntity.class;
            var idField = base.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 1L);
            var createdAtField = base.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(order, java.time.Instant.now());
            var updatedAtField = base.getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(order, java.time.Instant.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId(userId);
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(status);
        order.setShippingAddress("123 Main St, NYC");
        return order;
    }

    private OrderItem buildItem(Long productId, String name, int qty, BigDecimal price) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setProductName(name);
        item.setQuantity(qty);
        item.setUnitPrice(price);
        item.setSubtotal(price.multiply(BigDecimal.valueOf(qty)));
        return item;
    }

    private CreateOrderRequest buildCreateRequest(String userId) {
        return CreateOrderRequest.newBuilder()
                .setUserId(userId)
                .addItems(OrderItemRequest.newBuilder()
                        .setProductId("1")
                        .setProductName("Widget")
                        .setQuantity(2)
                        .setUnitPrice(Money.newBuilder().setAmount("49.99").setCurrency("USD").build())
                        .build())
                .setShippingAddress(Address.newBuilder()
                        .setStreet("123 Main St").setCity("NYC").setState("NY")
                        .setZip("10001").setCountry("US").build())
                .build();
    }

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        @Test
        void shouldReturnSuccessWhenOrderCreated() {
            Order order = buildOrder("user-1", OrderStatus.PENDING);
            when(orderService.createOrder(anyString(), anyList(), anyString())).thenReturn(order);

            grpcService.createOrder(buildCreateRequest("user-1"), createObserver);

            verify(createObserver).onNext(createResponseCaptor.capture());
            verify(createObserver).onCompleted();
            assertThat(createResponseCaptor.getValue().getSuccess()).isTrue();
            assertThat(createResponseCaptor.getValue().getMessage()).isNotBlank();
        }

        @Test
        void shouldReturnInternalWhenSagaFails() {
            when(orderService.createOrder(anyString(), anyList(), anyString()))
                    .thenThrow(new SagaStepException("Inventory unavailable", false));

            grpcService.createOrder(buildCreateRequest("user-1"), createObserver);

            verify(createObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }

        @Test
        void shouldReturnInvalidArgumentWhenIllegalArgument() {
            when(orderService.createOrder(anyString(), anyList(), anyString()))
                    .thenThrow(new IllegalArgumentException("Items cannot be empty"));

            grpcService.createOrder(buildCreateRequest("user-1"), createObserver);

            verify(createObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnInternalWhenUnexpectedExceptionOccurs() {
            when(orderService.createOrder(anyString(), anyList(), anyString()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            grpcService.createOrder(buildCreateRequest("user-1"), createObserver);

            verify(createObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("getOrder")
    class GetOrder {

        @Test
        void shouldReturnOrderDetailsWithItemsWhenFound() {
            Order order = buildOrder("user-1", OrderStatus.CONFIRMED);
            order.getItems().add(buildItem(1L, "Widget", 2, new BigDecimal("25.00")));
            when(orderService.getOrder(1L)).thenReturn(order);

            grpcService.getOrder(GetOrderRequest.newBuilder().setOrderId("1").build(), getObserver);

            verify(getObserver).onNext(getResponseCaptor.capture());
            verify(getObserver).onCompleted();
            GetOrderResponse response = getResponseCaptor.getValue();
            assertThat(response.getUserId()).isEqualTo("user-1");
            assertThat(response.getStatus()).isEqualTo("CONFIRMED");
            assertThat(response.getItemsCount()).isEqualTo(1);
        }

        @Test
        void shouldReturnNotFoundWhenOrderDoesNotExist() {
            when(orderService.getOrder(99L)).thenThrow(new ResourceNotFoundException("Order", 99L));

            grpcService.getOrder(GetOrderRequest.newBuilder().setOrderId("99").build(), getObserver);

            verify(getObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        void shouldReturnInvalidArgumentWhenOrderIdNotANumber() {
            grpcService.getOrder(GetOrderRequest.newBuilder().setOrderId("not-a-number").build(), getObserver);

            verify(getObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnInternalOnUnexpectedException() {
            when(orderService.getOrder(1L)).thenThrow(new RuntimeException("DB error"));

            grpcService.getOrder(GetOrderRequest.newBuilder().setOrderId("1").build(), getObserver);

            verify(getObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        @Test
        void shouldReturnSuccessWhenOrderCancelled() {
            when(orderService.cancelOrder(eq(1L), anyString(), anyString()))
                    .thenReturn(org.mockito.Mockito.mock(Order.class));

            CancelOrderRequest request = CancelOrderRequest.newBuilder()
                    .setOrderId("1")
                    .setReason("Customer changed mind")
                    .setCancelledBy("user-1")
                    .build();

            grpcService.cancelOrder(request, cancelObserver);

            verify(cancelObserver).onNext(cancelResponseCaptor.capture());
            verify(cancelObserver).onCompleted();
            assertThat(cancelResponseCaptor.getValue().getSuccess()).isTrue();
            assertThat(cancelResponseCaptor.getValue().getStatus()).isEqualTo("CANCELLED");
        }

        @Test
        void shouldReturnFailedPreconditionWhenOrderNotCancellable() {
            doThrow(new OrderNotCancellableException("Order already shipped"))
                    .when(orderService).cancelOrder(eq(2L), anyString(), anyString());

            CancelOrderRequest request = CancelOrderRequest.newBuilder()
                    .setOrderId("2").setReason("Customer changed mind").setCancelledBy("user-1").build();

            grpcService.cancelOrder(request, cancelObserver);

            verify(cancelObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION);
        }

        @Test
        void shouldReturnNotFoundWhenOrderMissing() {
            doThrow(new ResourceNotFoundException("Order", 99L))
                    .when(orderService).cancelOrder(eq(99L), anyString(), anyString());

            CancelOrderRequest request = CancelOrderRequest.newBuilder()
                    .setOrderId("99").setReason("Test").setCancelledBy("user-1").build();

            grpcService.cancelOrder(request, cancelObserver);

            verify(cancelObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        void shouldReturnInvalidArgumentWhenOrderIdNotANumber() {
            CancelOrderRequest request = CancelOrderRequest.newBuilder()
                    .setOrderId("bad-id").setReason("Test").setCancelledBy("user-1").build();

            grpcService.cancelOrder(request, cancelObserver);

            verify(cancelObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        void shouldReturnInternalOnUnexpectedException() {
            doThrow(new RuntimeException("DB error"))
                    .when(orderService).cancelOrder(eq(1L), anyString(), anyString());

            CancelOrderRequest request = CancelOrderRequest.newBuilder()
                    .setOrderId("1").setReason("Test").setCancelledBy("user-1").build();

            grpcService.cancelOrder(request, cancelObserver);

            verify(cancelObserver).onError(errorCaptor.capture());
            assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode())
                    .isEqualTo(Status.Code.INTERNAL);
        }
    }
}
