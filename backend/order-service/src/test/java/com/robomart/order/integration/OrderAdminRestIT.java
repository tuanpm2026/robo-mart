package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.test.PostgresContainerConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
@DisplayName("OrderAdminRestController Integration Tests")
class OrderAdminRestIT {

    @MockitoBean
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @MockitoBean
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Don't throw on error status codes — we assert them directly
                })
                .build();
    }

    private Order persistOrderInState(OrderStatus status, String userId) {
        return transactionTemplate.execute(txStatus -> {
            Order order = new Order();
            order.setUserId(userId);
            order.setTotalAmount(new BigDecimal("149.99"));
            order.setStatus(status);
            order.setShippingAddress("456 Admin St, Test, TX, 75001, US");
            Order saved = orderRepository.save(order);

            OrderItem item = new OrderItem();
            item.setOrder(saved);
            item.setProductId(100L);
            item.setProductName("Admin Test Product");
            item.setQuantity(3);
            item.setUnitPrice(new BigDecimal("49.99"));
            item.setSubtotal(new BigDecimal("149.97"));
            orderItemRepository.save(item);

            OrderStatusHistory history = new OrderStatusHistory();
            history.setOrder(saved);
            history.setStatus(status);
            history.setChangedAt(Instant.now());
            orderStatusHistoryRepository.save(history);

            return saved;
        });
    }

    @Test
    @DisplayName("shouldListOrdersWithPagination")
    void shouldListOrdersWithPagination() {
        persistOrderInState(OrderStatus.CONFIRMED, "user-admin-list");

        var response = restClient.get()
                .uri("/api/v1/admin/orders?page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("\"pagination\"");
        assertThat(response.getBody()).contains("\"totalElements\"");
        assertThat(response.getBody()).contains("\"userId\"");
    }

    @Test
    @DisplayName("shouldFilterOrdersByStatus")
    void shouldFilterOrdersByStatus() {
        persistOrderInState(OrderStatus.SHIPPED, "user-admin-filter");

        var response = restClient.get()
                .uri("/api/v1/admin/orders?page=0&size=10&statuses=SHIPPED")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"SHIPPED\"");
    }

    @Test
    @DisplayName("shouldReturnOrderDetail")
    void shouldReturnOrderDetail() {
        Order order = persistOrderInState(OrderStatus.CONFIRMED, "user-admin-detail");

        var response = restClient.get()
                .uri("/api/v1/admin/orders/{orderId}", order.getId())
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"userId\"");
        assertThat(response.getBody()).contains("user-admin-detail");
        assertThat(response.getBody()).contains("\"items\"");
        assertThat(response.getBody()).contains("\"statusHistory\"");
        assertThat(response.getBody()).contains("Admin Test Product");
    }

    @Test
    @DisplayName("shouldReturn404WhenOrderNotFound")
    void shouldReturn404WhenOrderNotFound() {
        var response = restClient.get()
                .uri("/api/v1/admin/orders/{orderId}", 999999L)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("shouldUpdateStatusFromConfirmedToShipped")
    void shouldUpdateStatusFromConfirmedToShipped() {
        Order order = persistOrderInState(OrderStatus.CONFIRMED, "user-admin-update");

        var response = restClient.put()
                .uri("/api/v1/admin/orders/{orderId}/status", order.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"status": "SHIPPED"}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"SHIPPED\"");

        // Verify in DB
        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("shouldUpdateStatusFromShippedToDelivered")
    void shouldUpdateStatusFromShippedToDelivered() {
        Order order = persistOrderInState(OrderStatus.SHIPPED, "user-admin-deliver");

        var response = restClient.put()
                .uri("/api/v1/admin/orders/{orderId}/status", order.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"status": "DELIVERED"}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"DELIVERED\"");

        Order persisted = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("shouldRejectInvalidStatusTransition")
    void shouldRejectInvalidStatusTransition() {
        Order order = persistOrderInState(OrderStatus.PENDING, "user-admin-reject");

        var response = restClient.put()
                .uri("/api/v1/admin/orders/{orderId}/status", order.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"status": "SHIPPED"}
                        """)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_TRANSITION");
    }
}
