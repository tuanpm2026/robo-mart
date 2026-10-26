package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.robomart.common.dto.ApiResponse;
import com.robomart.order.client.ProductCatalogClient;
import com.robomart.order.client.ProductSnapshot;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.saga.DeadSagaDetectionJob;
import com.robomart.order.service.OrderService;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;
import com.robomart.proto.payment.ProcessPaymentResponse;
import com.robomart.test.PostgresContainerConfig;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wave 6 checkout safety against a real database: server-side pricing, idempotent creation (including
 * the concurrent unique-index race), saga abort after a lost status CAS, and the recovery age cutoff.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Keep this context's scheduler out of the way; the test drives recovery explicitly.
        properties = "saga.dead-saga-detection.initial-delay-ms=3600000")
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, TestJwtDecoderConfig.class})
@DisplayName("Order checkout safety (Wave 6)")
class OrderCheckoutSafetyIT {

    private static final String ADDRESS = "1 Safety Way";

    @MockitoBean(answers = Answers.RETURNS_SELF)
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @MockitoBean(answers = Answers.RETURNS_SELF)
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockitoBean
    private ProductCatalogClient productCatalogClient;

    @LocalServerPort
    private int port;

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private DeadSagaDetectionJob deadSagaDetectionJob;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> leftovers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(inventoryStub, paymentStub);
        when(productCatalogClient.getProduct(10L)).thenReturn(
                new ApiResponse<>(new ProductSnapshot(10L, "Catalog Widget", new BigDecimal("49.99")), "trace"));
        when(productCatalogClient.getProduct(999L)).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        when(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class)))
                .thenReturn(ReserveInventoryResponse.newBuilder().setSuccess(true).setReservationId("res-w6").build());
        when(inventoryStub.releaseInventory(any(ReleaseInventoryRequest.class)))
                .thenReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build());
        when(paymentStub.processPayment(any(ProcessPaymentRequest.class)))
                .thenReturn(ProcessPaymentResponse.newBuilder().setSuccess(true).setPaymentId("pay-w6")
                        .setTransactionId("txn-w6").build());
    }

    @AfterEach
    void parkLeftovers() {
        // Keep in-flight fixtures out of other contexts' dead-saga scans.
        leftovers.forEach(id -> jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", id));
        leftovers.clear();
    }

    private RestClient client(String userId) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", TestJwt.customerBearer())
                .defaultHeader("X-User-Id", userId)
                .defaultStatusHandler(status -> status.isError(), (request, response) -> { })
                .build();
    }

    private static String body(String productId, String productName, String unitPrice) {
        return "{\"items\":[{\"productId\":\"" + productId + "\",\"productName\":\"" + productName
                + "\",\"quantity\":2,\"unitPrice\":" + unitPrice + "}],\"shippingAddress\":\"" + ADDRESS + "\"}";
    }

    private static JsonNode json(ResponseEntity<String> response) {
        return JsonMapper.builder().build().readTree(response.getBody());
    }

    @Test
    @DisplayName("HTTP checkout prices from the catalog and ignores the client's price and name")
    void clientPriceIsIgnored() {
        var response = client("w6-pricing").post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .body(body("10", "Free Widget", "0.01")).retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = json(response).path("data");
        assertThat(data.path("totalAmount").decimalValue()).isEqualByComparingTo("99.98");
        long id = data.path("id").asLong();
        assertThat(orderItemRepository.findByOrderId(id)).singleElement().satisfies(item -> {
            assertThat(item.getProductName()).isEqualTo("Catalog Widget");
            assertThat(item.getUnitPrice()).isEqualByComparingTo("49.99");
        });
        verify(paymentStub).processPayment(org.mockito.ArgumentMatchers.argThat(
                (ProcessPaymentRequest request) -> request.getAmount().getAmount().equals("99.98")));
    }

    @Test
    @DisplayName("HTTP checkout of an unknown or inactive product is rejected with 409 and creates nothing")
    void unknownProductIsRejected() {
        var response = client("w6-unknown").post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .body(body("999", "Ghost", "10.00")).retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).path("error").path("code").asString()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id='w6-unknown'", Integer.class))
                .isZero();
        verify(inventoryStub, never()).reserveInventory(any());
    }

    @Test
    @DisplayName("replaying an Idempotency-Key returns the original order and runs the saga once")
    void idempotentReplayOverHttp() {
        String key = UUID.randomUUID().toString();
        RestClient client = client("w6-replay");

        var first = client.post().uri("/api/v1/orders").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).body(body("10", "Widget", "49.99"))
                .retrieve().toEntity(String.class);
        var second = client.post().uri("/api/v1/orders").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).body(body("10", "Widget", "49.99"))
                .retrieve().toEntity(String.class);

        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).as(second.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(second).path("data").path("id").asLong()).isEqualTo(json(first).path("data").path("id").asLong());
        assertThat(json(second).path("data").path("status").asString()).isEqualTo("CONFIRMED");
        verify(inventoryStub, times(1)).reserveInventory(any());
        verify(paymentStub, times(1)).processPayment(any());
    }

    @Test
    @DisplayName("concurrent creates with one idempotency key resolve to a single order")
    void concurrentIdempotentCreates() throws Exception {
        String key = UUID.randomUUID().toString();
        int callers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return orderService.createOrder("w6-race", List.of(
                            new OrderService.OrderItemRequest("10", null, 2, null)), ADDRESS, key).getId();
                }));
            }
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (Future<Long> result : results) {
                ids.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id='w6-race' AND idempotency_key=?",
                Integer.class, key)).isEqualTo(1);
        verify(inventoryStub, times(1)).reserveInventory(any());
    }

    @Test
    @DisplayName("a saga whose order is cancelled mid-reservation never charges and never confirms")
    void sagaAbortsAfterLosingCas() {
        when(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class))).thenAnswer(invocation -> {
            ReserveInventoryRequest request = invocation.getArgument(0);
            // Recovery (or any other actor) takes the order over while the reservation is in flight.
            jdbc.update("UPDATE orders SET status='CANCELLED', version=version+1 WHERE id=?",
                    Long.parseLong(request.getOrderId()));
            return ReserveInventoryResponse.newBuilder().setSuccess(true).setReservationId("res-late").build();
        });

        Order order = orderService.createOrder("w6-cas", List.of(
                new OrderService.OrderItemRequest("10", null, 2, null)), ADDRESS, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentStub, never()).processPayment(any());
        verify(inventoryStub).releaseInventory(any(ReleaseInventoryRequest.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? "
                + "AND payload->>'newStatus'='CONFIRMED'", Integer.class, order.getId().toString())).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM order_status_history WHERE order_id=?", String.class,
                order.getId())).doesNotContain("PAYMENT_PROCESSING", "CONFIRMED");
    }

    @Test
    @DisplayName("dead-saga recovery only takes over orders idle longer than the threshold")
    void recoveryRespectsCutoff() {
        long stale = insertReservingOrder("10 minutes");
        long live = insertReservingOrder("5 seconds");

        // Bypass the ShedLock proxy: other cached test contexts may hold the shared lock row.
        DeadSagaDetectionJob job = AopTestUtils.getTargetObject(deadSagaDetectionJob);
        job.detectAndRecoverDeadSagas();

        assertThat(statusOf(stale)).isEqualTo("CANCELLED");
        assertThat(statusOf(live)).isEqualTo("INVENTORY_RESERVING");
        assertThat(jdbc.queryForList("SELECT status FROM order_status_history WHERE order_id=? ORDER BY id", String.class, stale))
                .containsExactly("INVENTORY_RELEASING", "CANCELLED");
        verify(inventoryStub, never()).releaseInventory(org.mockito.ArgumentMatchers.argThat(
                (ReleaseInventoryRequest request) -> request.getOrderId().equals(Long.toString(live))));
    }

    @Test
    void failedReleaseRemainsRecoverableUntilStockReleaseSucceeds() {
        when(paymentStub.processPayment(any())).thenThrow(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
        when(inventoryStub.releaseInventory(any())).thenThrow(io.grpc.Status.ABORTED.asRuntimeException());
        Order order = orderService.createOrder("w7-release", List.of(
                new OrderService.OrderItemRequest("10", null, 2, null)), ADDRESS, null);
        leftovers.add(order.getId());

        assertThat(statusOf(order.getId())).isEqualTo("INVENTORY_RELEASING");
        assertThat(jdbc.queryForList("SELECT status FROM order_status_history WHERE order_id=?", String.class,
                order.getId())).doesNotContain("CANCELLED");
        verify(inventoryStub, times(3)).releaseInventory(any());
        doReturn(ReleaseInventoryResponse.newBuilder().setSuccess(true).build())
                .when(inventoryStub).releaseInventory(any());
        jdbc.update("UPDATE orders SET updated_at=now() - interval '10 minutes' WHERE id=?", order.getId());

        DeadSagaDetectionJob job = AopTestUtils.getTargetObject(deadSagaDetectionJob);
        job.detectAndRecoverDeadSagas();

        assertThat(statusOf(order.getId())).isEqualTo("CANCELLED");
    }

    private long insertReservingOrder(String age) {
        Long id = jdbc.queryForObject("INSERT INTO orders (user_id, total_amount, status, shipping_address, "
                + "created_at, updated_at) VALUES ('w6-recovery', 10.00, 'INVENTORY_RESERVING', '" + ADDRESS + "', "
                + "now() - interval '" + age + "', now() - interval '" + age + "') RETURNING id", Long.class);
        jdbc.update("INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price, subtotal) "
                + "VALUES (?, 10, 'Widget', 1, 10.00, 10.00)", id);
        leftovers.add(id);
        return id;
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id);
    }
}
