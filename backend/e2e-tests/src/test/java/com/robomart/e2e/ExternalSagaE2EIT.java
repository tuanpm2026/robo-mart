package com.robomart.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Admission, replay, restart and charge-free compensation against the isolated real stack. */
class ExternalSagaE2EIT {
    private final String token = E2eSupport.customerToken();

    private ResponseEntity<String> create(E2eSupport.Product product, String key) {
        return E2eSupport.api()
                .post()
                .uri("/api/v1/orders")
                .headers(h -> h.setBearerAuth(token))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(E2eSupport.orderPayload(product, 1))
                .retrieve()
                .toEntity(String.class);
    }

    private long id(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isIn(200, 202);
        long id = E2eSupport.json(response.getBody()).path("data").path("id").asLong();
        assertThat(id).isPositive();
        return id;
    }

    private void cancel(long id) {
        var response =
                E2eSupport.api()
                        .post()
                        .uri("/api/v1/orders/" + id + "/cancel")
                        .headers(h -> h.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("reason", "Saga E2E cleanup"))
                        .retrieve()
                        .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isIn(200, 202);
        E2eSupport.awaitStatus(id, "CANCELLED");
        E2eSupport.recordSagaEvidence("E01", id, "charged-order-cleanup-compensated");
    }

    @Test
    void sagaOutageKeepsAdmissionReplayAndCancellationDurable() throws Exception {
        var product = E2eSupport.availableProduct();
        String key = UUID.randomUUID().toString();
        long id = 0;
        mutate("checkout-saga-service", "stop");
        try {
            var accepted = create(product, key);
            assertThat(accepted.getStatusCode().value()).isEqualTo(202);
            id = id(accepted);
            assertThat(id(create(product, key))).isEqualTo(id);
            var conflict =
                    E2eSupport.api()
                            .post()
                            .uri("/api/v1/orders")
                            .headers(h -> h.setBearerAuth(token))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(E2eSupport.orderPayload(product, 2))
                            .retrieve()
                            .toBodilessEntity();
            assertThat(conflict.getStatusCode().value()).isEqualTo(409);
            for (int i = 0; i < 3; i++) {
                var cancel =
                        E2eSupport.api()
                                .post()
                                .uri("/api/v1/orders/" + id + "/cancel")
                                .headers(h -> h.setBearerAuth(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("reason", "Cancel before dispatch"))
                                .retrieve()
                                .toBodilessEntity();
                assertThat(cancel.getStatusCode().value()).isEqualTo(202);
            }
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "order_db",
                                    "SELECT status FROM orders WHERE id=" + id))
                    .isEqualTo("CANCELLATION_PENDING");
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "order_db",
                                    "SELECT count(*) FROM outbox_events WHERE aggregate_id='"
                                            + id
                                            + "' AND event_type='checkout_saga_requested'"))
                    .isEqualTo("2");
        } finally {
            mutate("checkout-saga-service", "start");
        }
        E2eSupport.awaitStatus(id, "CANCELLED");
        E2eSupport.recordSagaEvidence("K01",id,"cancel-before-saga-dispatch-recovered");
        assertThat(
                        E2eSupport.query(
                                "postgres",
                                "payment_db",
                                "SELECT count(*) FROM mock_gateway_operations WHERE"
                                        + " request_order_id='"
                                        + id
                                        + "'"))
                .isEqualTo("0");
        assertThat(
                        E2eSupport.query(
                                "postgres",
                                "payment_db",
                                "SELECT count(*) FROM mock_gateway_charge_closures WHERE order_id='"
                                        + id
                                        + "'"))
                .isEqualTo("1");
        assertThat(
                        E2eSupport.query(
                                "postgres",
                                "inventory_db",
                                "SELECT status FROM reservations WHERE order_id='" + id + "'"))
                .isEqualTo("RELEASED");
    }

    @Test
    void killedWorkerResumesPersistedChargePhaseWithoutDuplicateEffects() throws Exception {
        var product = E2eSupport.availableProduct();
        long id = 0;
        mutate("payment-service", "stop");
        try {
            id = id(create(product, UUID.randomUUID().toString()));
            final long admitted = id;
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    E2eSupport.query(
                                                            "postgres",
                                                            "saga_db",
                                                            "SELECT state FROM saga_instances WHERE"
                                                                    + " order_id='"
                                                                    + admitted
                                                                    + "'"))
                                            .isEqualTo("CHARGE"));
            mutate("checkout-saga-service", "kill");
        } finally {
            mutate("payment-service", "start");
            mutate("checkout-saga-service", "start");
        }
        long restarted = System.nanoTime();
        try {
            E2eSupport.awaitStatus(id, "CONFIRMED");
            assertThat(Duration.ofNanos(System.nanoTime() - restarted))
                    .isLessThanOrEqualTo(Duration.ofSeconds(60));
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "payment_db",
                                    "SELECT count(*) FROM mock_gateway_operations WHERE"
                                            + " request_order_id='"
                                            + id
                                            + "' AND status='COMPLETED'"))
                    .isEqualTo("1");
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "inventory_db",
                                    "SELECT count(*) FROM stock_movements WHERE order_id='"
                                            + id
                                            + "' AND type='RESERVE'"))
                    .isEqualTo("1");
        } finally {
            if (id > 0) cancel(id);
        }
    }

    @Test
    void insufficientStockClosesChargeAndCancelsWithoutPayment() {
        String admin = E2eSupport.adminToken(),
                name = "SagaNoStock" + UUID.randomUUID().toString().replace("-", "");
        var created =
                E2eSupport.api()
                        .post()
                        .uri("/api/v1/admin/products")
                        .headers(h -> h.setBearerAuth(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "name",
                                        name,
                                        "categoryId",
                                        1,
                                        "price",
                                        "19.95",
                                        "sku",
                                        name,
                                        "brand",
                                        "Saga E2E"))
                        .retrieve()
                        .toEntity(String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long product = E2eSupport.json(created.getBody()).path("data").path("id").asLong();
        try {
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    E2eSupport.query(
                                                            "postgres",
                                                            "inventory_db",
                                                            "SELECT count(*) FROM inventory_items"
                                                                    + " WHERE product_id="
                                                                    + product))
                                            .isEqualTo("1"));
            long id =
                    id(
                            create(
                                    new E2eSupport.Product(
                                            product, name, new java.math.BigDecimal("19.95")),
                                    UUID.randomUUID().toString()));
            E2eSupport.awaitStatus(id, "CANCELLED");
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "payment_db",
                                    "SELECT count(*) FROM payments WHERE order_id='" + id + "'"))
                    .isEqualTo("0");
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "inventory_db",
                                    "SELECT count(*) FROM stock_movements WHERE order_id='"
                                            + id
                                            + "' AND type='RESERVE'"))
                    .isEqualTo("0");
        } finally {
            E2eSupport.api()
                    .delete()
                    .uri("/api/v1/admin/products/" + product)
                    .headers(h -> h.setBearerAuth(admin))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    @Test
    void concurrentClientReplaysCreateOneWorkflowAndOneCharge() throws Exception {
        var product = E2eSupport.availableProduct();
        String key = UUID.randomUUID().toString();
        long order;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests =
                    java.util.stream.IntStream.range(0, 6)
                            .mapToObj(i -> pool.submit(() -> id(create(product, key))))
                            .toList();
            order = requests.getFirst().get(30, TimeUnit.SECONDS);
            for (var request : requests)
                assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo(order);
        }
        try {
            E2eSupport.awaitStatus(order, "CONFIRMED");
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "saga_db",
                                    "SELECT count(*) FROM saga_instances WHERE order_id='"
                                            + order
                                            + "'"))
                    .isEqualTo("1");
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "payment_db",
                                    "SELECT count(*) FROM mock_gateway_operations WHERE"
                                            + " request_order_id='"
                                            + order
                                            + "' AND status='COMPLETED'"))
                    .isEqualTo("1");
        } finally {
            cancel(order);
        }
    }

    private static void mutate(String service, String action) throws Exception {
        assertThat(E2eSupport.env("E2E_COMPOSE_PROJECT", ""))
                .isEqualTo("robomart-java25-e2e-resume");
        assertThat(List.of("checkout-saga-service", "payment-service")).contains(service);
        assertThat(List.of("start", "stop", "kill")).contains(action);
        String container = E2eSupport.container(service);
        var output = java.nio.file.Files.createTempFile("saga-e2e-", ".log");
        try {
            Process process =
                    new ProcessBuilder(E2eSupport.dockerExecutable(), action, container)
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            java.nio.file.Files.deleteIfExists(output);
        }
    }
}
