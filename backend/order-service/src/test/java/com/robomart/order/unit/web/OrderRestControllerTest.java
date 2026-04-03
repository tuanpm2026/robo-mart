package com.robomart.order.unit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.dto.PagedResponse;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.service.OrderService;
import com.robomart.order.web.CreateOrderItemRequest;
import com.robomart.order.web.CreateOrderRequest;
import com.robomart.order.web.OrderDetailResponse;
import com.robomart.order.web.OrderRestController;
import com.robomart.order.web.OrderSummaryResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderRestController")
class OrderRestControllerTest {

    @Mock
    private OrderService orderService;

    private OrderRestController controller;

    @BeforeEach
    void setUp() {
        controller = new OrderRestController(orderService);
    }

    // --- listOrders ---

    @Test
    @DisplayName("listOrders with valid userId returns 200 with paged response")
    void listOrders_validUserId_returnsPagedResponse() {
        String userId = "user-1";
        OrderSummaryResponse summary = new OrderSummaryResponse(
                1L, Instant.now(), new BigDecimal("99.99"), OrderStatus.CONFIRMED, 2, null);
        Page<OrderSummaryResponse> page = new PageImpl<>(
                List.of(summary), PageRequest.of(0, 10), 1);
        when(orderService.getOrdersByUser(userId, 0, 10)).thenReturn(page);

        ResponseEntity<PagedResponse<OrderSummaryResponse>> response = controller.listOrders(userId, 0, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).hasSize(1);
        assertThat(response.getBody().data().get(0).id()).isEqualTo(1L);
        assertThat(response.getBody().pagination().totalElements()).isEqualTo(1L);
        assertThat(response.getBody().pagination().page()).isEqualTo(0);
        assertThat(response.getBody().pagination().size()).isEqualTo(10);
    }

    @Test
    @DisplayName("listOrders with null userId returns 200 with empty page")
    void listOrders_nullUserId_returnsEmptyPage() {
        ResponseEntity<PagedResponse<OrderSummaryResponse>> response = controller.listOrders(null, 0, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEmpty();
        assertThat(response.getBody().pagination().totalElements()).isEqualTo(0L);
        assertThat(response.getBody().pagination().totalPages()).isEqualTo(0);
    }

    @Test
    @DisplayName("listOrders with blank userId returns 200 with empty page")
    void listOrders_blankUserId_returnsEmptyPage() {
        ResponseEntity<PagedResponse<OrderSummaryResponse>> response = controller.listOrders("   ", 0, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEmpty();
        assertThat(response.getBody().pagination().totalElements()).isEqualTo(0L);
    }

    // --- getOrder ---

    @Test
    @DisplayName("getOrder with valid orderId and userId returns 200 with order detail")
    void getOrder_validOrderIdAndUserId_returnsOrderDetail() {
        String userId = "user-1";
        Long orderId = 42L;
        OrderDetailResponse detail = new OrderDetailResponse(
                orderId, Instant.now(), Instant.now(),
                new BigDecimal("149.99"), OrderStatus.CONFIRMED,
                "123 Main St", null, List.of(), List.of());
        when(orderService.getOrderForUser(orderId, userId)).thenReturn(detail);

        var response = controller.getOrder(orderId, userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isNotNull();
        assertThat(response.getBody().data().id()).isEqualTo(orderId);
        assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("getOrder with null userId throws ResourceNotFoundException")
    void getOrder_nullUserId_throwsResourceNotFoundException() {
        assertThatThrownBy(() -> controller.getOrder(42L, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    @DisplayName("getOrder with blank userId throws ResourceNotFoundException")
    void getOrder_blankUserId_throwsResourceNotFoundException() {
        assertThatThrownBy(() -> controller.getOrder(42L, "  "))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("42");
    }

    // --- createOrder ---

    private Order buildOrder(Long id, String userId, OrderStatus status, BigDecimal totalAmount) {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId(userId);
        order.setStatus(status);
        order.setTotalAmount(totalAmount);
        order.setItems(List.of());
        return order;
    }

    @Test
    @DisplayName("createOrder with valid request and confirmed saga returns 201")
    void createOrder_validRequest_sagaConfirmed_returns201() {
        String userId = "user-1";
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateOrderItemRequest("1", "Product A", 2, new BigDecimal("49.99"))),
                "123 Main St, City, State 12345");

        Order mockOrder = buildOrder(10L, userId, OrderStatus.CONFIRMED, new BigDecimal("99.98"));

        when(orderService.createOrder(eq(userId), anyList(), eq(request.shippingAddress())))
                .thenReturn(mockOrder);

        ResponseEntity<ApiResponse<OrderSummaryResponse>> response = controller.createOrder(request, userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().id()).isEqualTo(10L);
        assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("createOrder with null userId throws ResourceNotFoundException")
    void createOrder_nullUserId_throwsResourceNotFoundException() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateOrderItemRequest("1", "Product A", 1, new BigDecimal("10.00"))),
                "123 Main St");

        assertThatThrownBy(() -> controller.createOrder(request, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createOrder when saga fails with payment throws OrderPaymentFailedException")
    void createOrder_sagaPaymentFailed_throwsOrderPaymentFailedException() {
        String userId = "user-1";
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateOrderItemRequest("1", "Product A", 1, new BigDecimal("10.00"))),
                "123 Main St");

        Order cancelledOrder = buildOrder(11L, userId, OrderStatus.CANCELLED, new BigDecimal("10.00"));
        cancelledOrder.setCancellationReason("Payment declined");

        when(orderService.createOrder(eq(userId), anyList(), anyString()))
                .thenReturn(cancelledOrder);

        assertThatThrownBy(() -> controller.createOrder(request, userId))
                .isInstanceOf(com.robomart.order.exception.OrderPaymentFailedException.class)
                .hasMessageContaining("Payment declined");
    }

    @Test
    @DisplayName("createOrder when saga fails with inventory throws OrderInventoryFailedException")
    void createOrder_sagaInventoryFailed_throwsOrderInventoryFailedException() {
        String userId = "user-1";
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateOrderItemRequest("1", "Product A", 1, new BigDecimal("10.00"))),
                "123 Main St");

        Order cancelledOrder = buildOrder(12L, userId, OrderStatus.CANCELLED, new BigDecimal("10.00"));
        cancelledOrder.setCancellationReason("Insufficient stock");

        when(orderService.createOrder(eq(userId), anyList(), anyString()))
                .thenReturn(cancelledOrder);

        assertThatThrownBy(() -> controller.createOrder(request, userId))
                .isInstanceOf(com.robomart.order.exception.OrderInventoryFailedException.class)
                .hasMessageContaining("Insufficient stock");
    }
}
