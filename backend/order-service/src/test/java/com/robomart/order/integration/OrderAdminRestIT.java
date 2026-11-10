package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
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
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, TestJwtDecoderConfig.class})
@DisplayName("OrderAdminRestController Integration Tests")
class OrderAdminRestIT {
    @Autowired private com.robomart.order.service.OrderService orders;

    @MockitoBean(answers = Answers.RETURNS_SELF)
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @MockitoBean(answers = Answers.RETURNS_SELF)
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
                // Service-level security now requires a valid ADMIN JWT on /api/v1/admin/** (defense
                // in depth). The test decoder (TestJwtDecoderConfig) resolves this token to an ADMIN.
                .defaultHeader("Authorization", TestJwt.adminBearer())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Don't throw on error status codes — we assert them directly
                })
                .build();
    }

    @Test
    @DisplayName("shouldRejectAdminRequestWithoutToken")
    void shouldRejectAdminRequestWithoutToken() {
        RestClient anonymous = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();

        var response = anonymous.get()
                .uri("/api/v1/admin/orders?page=0&size=10")
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void statelessApiRejectsAmbientCredentialsAndRetainsCsrfElsewhere() {
        var client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        for (var headers : java.util.List.of(
                java.util.Map.<String, String>of(),
                java.util.Map.of("Cookie", "access_token=" + TestJwt.ADMIN_TOKEN + "; JSESSIONID=ignored"),
                java.util.Map.of("Authorization", "Basic YWRtaW46cGFzc3dvcmQ="))) {
            assertThat(client.post().uri("/api/v1/admin/orders/1/status")
                    .headers(h -> headers.forEach(h::set)).contentType(MediaType.APPLICATION_JSON)
                    .body("{}").retrieve().toBodilessEntity().getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(client.get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode())
                .isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(client.post().uri("/actuator/health").header("Cookie", "access_token=" + TestJwt.ADMIN_TOKEN)
                .retrieve().toBodilessEntity().getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void inventoryIdentityCanOnlyReadMinimalInternalStatus() {
        Order order = persistOrderInState(OrderStatus.PAYMENT_PENDING, "expiry-customer");
        RestClient inventory = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestJwt.INVENTORY_TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        String path = "/api/v1/internal/inventory/orders/" + order.getId() + "/status";
        var status = inventory.get().uri(path).retrieve().toEntity(String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("PAYMENT_PENDING").doesNotContain("expiry-customer");
        assertThat(inventory.get().uri("/api/v1/admin/orders/" + order.getId()).retrieve()
                .toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inventory.post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).body("{}")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restClient.get().uri(path).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        RestClient customer = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", TestJwt.customerBearer())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        assertThat(customer.get().uri(path).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
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
    void shouldAllowNotificationServiceToReadOnlyOrderDetail() {
        Order order = persistOrderInState(OrderStatus.CONFIRMED, "notification-read-test");
        RestClient machineClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestJwt.NOTIFICATION_TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
        assertThat(machineClient.get().uri("/api/v1/admin/orders/{id}", order.getId())
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(machineClient.get().uri("/api/v1/admin/orders")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(machineClient.get().uri("/api/v1/admin/orders/metrics")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(machineClient.put().uri("/api/v1/admin/orders/{id}/status", order.getId())
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"SHIPPED\"}")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(machineClient.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).body("{}")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
    @Test
    void notificationIdentityCanReadOnlyRequiredOperationsEndpoints() {
        RestClient operations = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestJwt.NOTIFICATION_TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        assertThat(operations.get().uri("/api/v1/admin/orders/reconciliation-summary").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(operations.get().uri("/api/v1/admin/orders/audit-logs?size=100").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(operations.get().uri("/api/v1/admin/orders/audit-logs?size=101").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(operations.get().uri("/api/v1/admin/orders/metrics").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(operations.put().uri("/api/v1/admin/orders/1/status").contentType(MediaType.APPLICATION_JSON).body("{}")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        RestClient customer = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", TestJwt.customerBearer())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        assertThat(customer.get().uri("/api/v1/admin/orders/audit-logs").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cancelledAndUnpaidOrdersAreExcludedFromRevenueButIncludedInReconciliation() {
        var since = java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        var before = orderRepository.sumTotalAmountByCreatedAtAfter(since);
        var cancelled = persistOrderInState(OrderStatus.CANCELLED, "cancelled-ledger");
        persistOrderInState(OrderStatus.PAYMENT_PENDING, "unpaid-ledger");
        persistOrderInState(OrderStatus.CONFIRMED, "paid-ledger");
        assertThat(orderRepository.sumTotalAmountByCreatedAtAfter(since).subtract(before)).isEqualByComparingTo("149.99");
        var ids = new java.util.HashSet<String>();
        for (int page = 0; page < 100; page++) {
            var rows = orders.getOrderReconciliationSummary(page);
            rows.forEach(row -> ids.add(row.orderId()));
            if (rows.size() < 100) break;
        }
        assertThat(ids).contains(cancelled.getId().toString());
    }

}
