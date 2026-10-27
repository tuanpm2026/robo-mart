package com.robomart.e2e.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import com.robomart.e2e.E2eSupport;

import tools.jackson.databind.JsonNode;

/** Real fault injection against the explicitly selected disposable Compose project. */
@EnabledIfSystemProperty(named = "chaos.enabled", matches = "true")
class ServiceKillChaosIT {

    private RestClient api;
    private RestClient inventory;
    private RestClient order;
    private RestClient payment;
    private String token;
    private String paymentContainer;
    private boolean paymentStopped;
    private boolean chaosTouched;
    private final List<Long> confirmedOrders = new ArrayList<>();
    private final List<Long> recoveryOrders = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assertThat(E2eSupport.env("E2E_COMPOSE_PROJECT", ""))
                .as("Chaos may only mutate the dedicated disposable project")
                .isEqualTo("robomart-java25-e2e-resume");
        api = E2eSupport.api();
        inventory = E2eSupport.client(E2eSupport.env("E2E_INVENTORY_URL", "http://localhost:18084"));
        order = E2eSupport.client(E2eSupport.env("E2E_ORDER_URL", "http://localhost:18083"));
        payment = E2eSupport.client(E2eSupport.env("E2E_PAYMENT_URL", "http://localhost:18086"));
        token = E2eSupport.customerToken();
        paymentContainer = E2eSupport.container("payment-service");
        await().atMost(Duration.ofSeconds(60)).ignoreException(ResourceAccessException.class)
                .untilAsserted(() -> assertHealthy(payment));
        await().atMost(Duration.ofSeconds(60)).ignoreException(ResourceAccessException.class)
                .untilAsserted(() -> assertHealthy(order));
    }

    @AfterEach
    void restoreFaultsAndCancelConfirmedOrders() throws Exception {
        try {
            if (paymentStopped) {
                mutatePaymentContainer("start");
                paymentStopped = false;
                await().atMost(Duration.ofSeconds(60)).ignoreException(ResourceAccessException.class)
                        .untilAsserted(() -> assertHealthy(payment));
            }
        } finally {
            try {
                if (chaosTouched) {
                    assertStatus(inventory.post().uri("/actuator/chaosmonkey/disable")
                            .retrieve().toEntity(String.class), 200);
                    assertStatus(inventory.post().uri("/actuator/chaosmonkey/assaults")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("latencyActive", false, "exceptionsActive", false,
                                    "killApplicationActive", false, "memoryActive", false, "cpuActive", false))
                            .retrieve().toEntity(String.class), 200);
                    assertThat(body(inventory.get().uri("/actuator/chaosmonkey/status")
                            .retrieve().toEntity(String.class), 200).path("enabled").asBoolean()).isFalse();
                    chaosTouched = false;
                }
            } finally {
                try {
                    if (!recoveryOrders.isEmpty()) {
                        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                                .untilAsserted(this::observeAllRecoveredOrders);
                    }
                } finally {
                    AssertionError cleanupFailure = null;
                    for (long id : confirmedOrders) {
                        try {
                            cancelAndAssertRefund(id);
                        } catch (Exception | AssertionError failure) {
                            if (cleanupFailure == null) {
                                cleanupFailure = new AssertionError("Some confirmed orders could not be cleaned up");
                            }
                            cleanupFailure.addSuppressed(failure);
                        }
                    }
                    if (cleanupFailure != null) {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    @Test
    void paymentOutageAutomaticallyResumesTheSameOrdersWithoutDuplicateCharges() throws Exception {
        E2eSupport.Product product = E2eSupport.availableProduct();
        // Checkout orchestrates payment synchronously: stop BEFORE requesting checkout.
        paymentStopped = true;
        mutatePaymentContainer("stop");
        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<String> response = createOrder(product);
            JsonNode data = body(response, 202).path("data");
            assertThat(data.path("status").asString()).isEqualTo("PAYMENT_PENDING");
            long id = positiveOrderId(data);
            recoveryOrders.add(id);
            assertPendingWithoutPayment(id);
        }
        assertThat(recoveryOrders).hasSize(5).doesNotHaveDuplicates();
        assertThat(metric(order, "resilience4j.circuitbreaker.state",
                "name:payment-service", "state:open")).isEqualTo(1.0);

        long restartStarted = System.nanoTime();
        mutatePaymentContainer("start");
        paymentStopped = false;
        await().atMost(recoveryBudget(restartStarted)).ignoreException(ResourceAccessException.class)
                .untilAsserted(() -> assertHealthy(payment));
        // Only the scheduled recovery worker may probe the breaker and resume these orders.
        // Do not submit a fresh checkout to trigger HALF_OPEN/CLOSED transitions.
        await().pollInterval(Duration.ofSeconds(2)).atMost(recoveryBudget(restartStarted))
                .untilAsserted(() -> {
                    observeAllRecoveredOrders();
                    for (long id : recoveryOrders) {
                        assertRecoveredExactlyOnce(id);
                    }
                    assertThat(metric(order, "resilience4j.circuitbreaker.state",
                            "name:payment-service", "state:closed")).isEqualTo(1.0);
                });
        assertThat(Duration.ofNanos(System.nanoTime() - restartStarted))
                .as("The same five pending orders confirm and the breaker closes within 60s of restart")
                .isLessThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(confirmedOrders).containsExactlyInAnyOrderElementsOf(recoveryOrders);
    }

    private void observeAllRecoveredOrders() {
        List<String> statuses = new ArrayList<>();
        for (long id : recoveryOrders) {
            String status = E2eSupport.query("postgres", "order_db", "SELECT status FROM orders WHERE id=" + id);
            if ("CONFIRMED".equals(status) && !confirmedOrders.contains(id)) {
                // Register cleanup immediately, even if a later order/assertion is still pending.
                confirmedOrders.add(id);
            }
            statuses.add(status);
        }
        assertThat(statuses).as("All original pending orders must resume automatically")
                .containsOnly("CONFIRMED");
    }

    private void assertRecoveredExactlyOnce(long id) {
        assertCommittedStock(id);
        assertThat(E2eSupport.query("postgres", "payment_db",
                "SELECT count(*) || '|' || count(*) FILTER (WHERE status='COMPLETED')"
                        + " FROM payments WHERE order_id='" + id + "'"))
                .as("Exactly one completed payment and no duplicate charge records for order %s", id)
                .isEqualTo("1|1");
        assertThat(E2eSupport.query("postgres", "payment_db",
                "SELECT count(*) FROM mock_gateway_operations WHERE operation_key='charge:" + id
                        + "' AND status='COMPLETED'"))
                .as("Exactly one completed provider operation is recorded for order %s", id).isEqualTo("1");
        assertThat(E2eSupport.query("postgres", "payment_db",
                "SELECT count(*) FROM payments WHERE order_id='" + id + "' AND processing_token IS NULL"))
                .as("Recovered payment has a finalized durable intent for order %s", id).isEqualTo("1");
        assertThat(E2eSupport.query("postgres", "inventory_db",
                "SELECT count(*) || '|' || count(*) FILTER (WHERE quantity=1)"
                        + " FROM stock_movements WHERE order_id='" + id + "' AND type='RESERVE'"))
                .as("Recovery must reuse the original inventory reservation for order %s", id)
                .isEqualTo("1|1");
        assertThat(E2eSupport.query("postgres", "order_db",
                "SELECT count(*) FILTER (WHERE step_name='ReserveInventory') || '|'"
                        + " || count(*) FILTER (WHERE step_name='ProcessPayment')"
                        + " FROM saga_audit_log WHERE order_id='" + id + "' AND status='SUCCESS'"))
                .as("Both forward steps complete once for order %s", id).isEqualTo("1|1");
        assertThat(E2eSupport.query("postgres", "order_db",
                "SELECT count(*) FROM outbox_events WHERE aggregate_id='" + id
                        + "' AND event_type='order_status_changed' AND payload->>'newStatus'='CONFIRMED'"))
                .as("A single confirmation event is created for order %s", id).isEqualTo("1");
        assertThat(E2eSupport.query("postgres", "order_db",
                "SELECT count(*) FROM order_status_history WHERE order_id=" + id + " AND status='CONFIRMED'"))
                .as("A single confirmation transition is persisted for order %s", id).isEqualTo("1");
    }

    @Test
    void serviceLayerLatencyAssaultIsAppliedAndCheckoutStillConfirms() throws Exception {
        E2eSupport.Product product = E2eSupport.availableProduct();
        JsonNode originalStatus = body(inventory.get().uri("/actuator/chaosmonkey/status")
                .retrieve().toEntity(String.class), 200);
        assertThat(originalStatus.path("enabled").asBoolean()).isFalse();
        chaosTouched = true;
        assertStatus(inventory.post().uri("/actuator/chaosmonkey/watchers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("service", true, "controller", false, "restController", false,
                        "repository", false, "component", false))
                .retrieve().toEntity(String.class), 200);
        assertStatus(inventory.post().uri("/actuator/chaosmonkey/assaults")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("level", 1, "deterministic", true, "latencyActive", true,
                        "latencyRangeStart", 500, "latencyRangeEnd", 500,
                        "exceptionsActive", false, "killApplicationActive", false,
                        "memoryActive", false, "cpuActive", false))
                .retrieve().toEntity(String.class), 200);
        assertThat(body(inventory.post().uri("/actuator/chaosmonkey/enable")
                .retrieve().toEntity(String.class), 200).path("enabled").asBoolean()).isTrue();
        JsonNode assault = body(inventory.get().uri("/actuator/chaosmonkey/assaults")
                .retrieve().toEntity(String.class), 200);
        assertThat(assault.path("latencyActive").asBoolean()).isTrue();
        assertThat(assault.path("latencyRangeStart").asInt()).isEqualTo(500);
        assertThat(assault.path("level").asInt()).isEqualTo(1);
        assertThat(body(inventory.get().uri("/actuator/chaosmonkey/watchers")
                .retrieve().toEntity(String.class), 200).path("service").asBoolean()).isTrue();

        ResponseEntity<String> baseline = inventory.get()
                .uri("/actuator/metrics/chaos.monkey.assault.latency.count").retrieve().toEntity(String.class);
        double beforeAssaults;
        if (baseline.getStatusCode().value() == 404) {
            beforeAssaults = 0.0; // No latency assault has ever run in this fresh stack.
        } else {
            beforeAssaults = body(baseline, 200).path("measurements").get(0).path("value").asDouble();
        }
        long started = System.nanoTime();
        JsonNode data = body(createOrder(product), 201).path("data");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        long id = positiveOrderId(data);
        confirmedOrders.add(id);
        assertThat(elapsedMillis).as("Observed service-layer latency assault").isGreaterThanOrEqualTo(500);
        assertThat(metric(inventory, "chaos.monkey.assault.latency.count"))
                .as("A real latency assault must execute during checkout").isGreaterThan(beforeAssaults);
        assertThat(data.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(E2eSupport.query("postgres", "order_db", "SELECT status FROM orders WHERE id=" + id))
                .isEqualTo("CONFIRMED");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertCommittedStock(id));
    }

    private void assertCommittedStock(long id) {
        assertThat(E2eSupport.query("postgres", "inventory_db",
                "SELECT r.status || '|' || (SELECT count(*) FROM stock_movements m WHERE m.order_id=r.order_id "
                        + "AND m.type='COMMIT' AND m.quantity=1) FROM reservations r WHERE r.order_id='" + id + "'"))
                .as("Confirmed order %s commits its original reservation once", id).isEqualTo("COMMITTED|1");
    }

    private static Duration recoveryBudget(long restartStarted) {
        Duration remaining = Duration.ofSeconds(60).minus(Duration.ofNanos(System.nanoTime() - restartStarted));
        assertThat(remaining).as("The shared 60s recovery budget must not be exhausted").isPositive();
        return remaining;
    }

    private ResponseEntity<String> createOrder(E2eSupport.Product product) {
        return api.post().uri("/api/v1/orders").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(E2eSupport.orderPayload(product, 1))
                .retrieve().toEntity(String.class);
    }

    private void assertPendingWithoutPayment(long id) throws Exception {
        assertThat(E2eSupport.query("postgres", "order_db", "SELECT status FROM orders WHERE id=" + id))
                .isEqualTo("PAYMENT_PENDING");
        assertThat(E2eSupport.query("postgres", "inventory_db",
                "SELECT status FROM reservations WHERE order_id='" + id + "'")).isEqualTo("RESERVED");
        assertThat(E2eSupport.query("postgres", "payment_db",
                "SELECT count(*) FROM payments WHERE order_id='" + id + "'")).isEqualTo("0");
    }

    private void cancelAndAssertRefund(long id) throws Exception {
        assertStatus(api.post().uri("/api/v1/orders/" + id + "/cancel")
                .headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Chaos verification cleanup")).retrieve().toEntity(String.class), 200);
        assertThat(E2eSupport.query("postgres", "order_db", "SELECT status FROM orders WHERE id=" + id))
                .isEqualTo("CANCELLED");
        assertThat(E2eSupport.query("postgres", "payment_db",
                "SELECT status FROM payments WHERE order_id='" + id + "'")).isEqualTo("REFUNDED");
        assertThat(E2eSupport.query("postgres", "inventory_db",
                "SELECT status FROM reservations WHERE order_id='" + id + "'")).isEqualTo("RELEASED");
    }

    private void mutatePaymentContainer(String action) throws Exception {
        // Resolve the exact project/service labels again immediately before each mutation.
        assertThat(E2eSupport.container("payment-service")).isEqualTo(paymentContainer);
        Path output = Files.createTempFile("robomart-chaos-docker-", ".log");
        try {
            Process process = new ProcessBuilder(E2eSupport.dockerExecutable(), action, paymentContainer)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker " + action + " timed out");
            }
            assertThat(process.exitValue()).as("docker %s: %s", action, Files.readString(output)).isZero();
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static void assertHealthy(RestClient service) {
        assertThat(body(service.get().uri("/actuator/health").retrieve().toEntity(String.class), 200)
                .path("status").asString()).isEqualTo("UP");
    }

    private static double metric(RestClient service, String name, String... tags) {
        JsonNode metric = body(service.get().uri(builder -> {
            builder.path("/actuator/metrics/" + name);
            for (String tag : tags) {
                builder.queryParam("tag", tag);
            }
            return builder.build();
        }).retrieve().toEntity(String.class), 200);
        assertThat(metric.path("measurements").size()).isEqualTo(1);
        return metric.path("measurements").get(0).path("value").asDouble();
    }

    private static long positiveOrderId(JsonNode data) {
        long id = data.path("id").asLong();
        assertThat(id).isPositive();
        return id;
    }

    private static JsonNode body(ResponseEntity<String> response, int status) {
        assertStatus(response, status);
        assertThat(response.getBody()).isNotBlank();
        return E2eSupport.json(response.getBody());
    }

    private static void assertStatus(ResponseEntity<String> response, int status) {
        assertThat(response.getStatusCode().value()).as("Response: %s", response.getBody()).isEqualTo(status);
    }
}
