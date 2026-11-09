package com.robomart.e2e;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

class SagaDurabilityE2EIT {
    private final String customer = E2eSupport.customerToken();

    private long create(String key, int expected) {
        var product = E2eSupport.availableProduct();
        var response =
                E2eSupport.api()
                        .post()
                        .uri("/api/v1/orders")
                        .headers(h -> h.setBearerAuth(customer))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(E2eSupport.orderPayload(product, 1))
                        .retrieve()
                        .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(expected);
        return E2eSupport.json(response.getBody()).path("data").path("id").asLong();
    }

    private void cancel(long id) {
        var response =
                E2eSupport.api()
                        .post()
                        .uri("/api/v1/orders/" + id + "/cancel")
                        .headers(h -> h.setBearerAuth(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("reason", "Durability fixture cleanup"))
                        .retrieve()
                        .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isIn(200, 202);
        E2eSupport.awaitStatus(id, "CANCELLED");
    }

    @Test
    void originalOrdersConfirmAfterSagaAdmissionOutage() {
        String saga = E2eSupport.container("checkout-saga-service");
        long id;
        E2eSupport.mutateFixture("checkout-saga-service", saga, "stop");
        try {
            id = create(UUID.randomUUID().toString(), 202);
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "order_db",
                                    "SELECT status FROM orders WHERE id=" + id))
                    .isEqualTo("PENDING");
        } finally {
            E2eSupport.mutateFixture("checkout-saga-service", saga, "start");
        }
        try {
            E2eSupport.awaitStatus(id, "CONFIRMED");
            oneCharge(id);
        } finally {
            cancel(id);
        }
    }

    @Test
    void kafkaOutageRetainsCreationAndCancellationUntilRecovery() {
        String kafka = E2eSupport.container("kafka");
        long id;
        E2eSupport.mutateFixture("kafka", kafka, "stop");
        try {
            id = create(UUID.randomUUID().toString(), 202);
            var accepted =
                    E2eSupport.api()
                            .post()
                            .uri("/api/v1/orders/" + id + "/cancel")
                            .headers(h -> h.setBearerAuth(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("reason", "Broker outage cancellation"))
                            .retrieve()
                            .toBodilessEntity();
            assertThat(accepted.getStatusCode().value()).isEqualTo(202);
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "order_db",
                                    "SELECT count(*) FROM outbox_events WHERE aggregate_id='"
                                            + id
                                            + "' AND event_type='checkout_saga_requested' AND"
                                            + " published=false"))
                    .isEqualTo("2");
        } finally {
            E2eSupport.mutateFixture("kafka", kafka, "start");
        }
        E2eSupport.awaitStatus(id, "CANCELLED");
        assertThat(
                        E2eSupport.query(
                                "postgres",
                                "payment_db",
                                "SELECT count(*) FROM mock_gateway_operations WHERE"
                                        + " request_order_id='"
                                        + id
                                        + "'"))
                .isEqualTo("0");
    }

    @Test
    void anotherReplicaRecoversChargeCommittedBeforeLostReply() throws Exception {
        long order = 0;
        String killed = null;
        try (var barrier = new PaymentReplyBarrier()) {
            E2eSupport.configureSagaReplicas("static://host.docker.internal:19096", 2);
            try {
                order = create(UUID.randomUUID().toString(), 202);
                assertThat(barrier.committed.await(30, TimeUnit.SECONDS))
                        .as("Real Payment must commit before fault injection")
                        .isTrue();
                String dispatcher = barrier.dispatcher.get();
                assertThat(dispatcher).isNotBlank();
                killed =
                        E2eSupport.containers("checkout-saga-service").stream()
                                .filter(
                                        id ->
                                                id.startsWith(dispatcher)
                                                        || dispatcher.startsWith(id))
                                .findFirst()
                                .orElseThrow();
                oneCharge(order);
                E2eSupport.recordSagaEvidence(
                        "E05", order, "provider-committed-before-withheld-reply");
                assertThat(
                                E2eSupport.query(
                                        "postgres",
                                        "saga_db",
                                        "SELECT state FROM saga_instances WHERE order_id='"
                                                + order
                                                + "'"))
                        .isEqualTo("CHARGE");
                E2eSupport.mutateFixture("checkout-saga-service", killed, "kill");
                barrier.release.countDown();
                E2eSupport.awaitStatus(order, "CONFIRMED");
                oneCharge(order);
                E2eSupport.recordSagaEvidence(
                        "E05", order, "other-replica-recovered-original-receipt");
                assertThat(
                                E2eSupport.query(
                                        "postgres",
                                        "order_db",
                                        "SELECT count(*) FROM order_status_history WHERE order_id="
                                                + order
                                                + " AND status='CONFIRMED'"))
                        .isEqualTo("1");
                assertThat(
                                E2eSupport.query(
                                        "postgres",
                                        "inventory_db",
                                        "SELECT count(*) FROM stock_movements WHERE order_id='"
                                                + order
                                                + "' AND type='RESERVE'"))
                        .isEqualTo("1");
            } finally {
                barrier.release.countDown();
                E2eSupport.configureSagaReplicas("static://payment-service:9095", 1);
            }
        } finally {
            if (order > 0) {
                E2eSupport.awaitStatus(order, "CONFIRMED");
                cancel(order);
            }
        }
    }

    @Test
    void admissionRollbackKeepsExistingExternalWorkflowOwnedAndFinishesLegacyOrder() {
        String saga = E2eSupport.container("checkout-saga-service");
        long external = 0, embedded = 0;
        E2eSupport.mutateFixture("checkout-saga-service", saga, "stop");
        try {
            external = create(UUID.randomUUID().toString(), 202);
            E2eSupport.configureAdmission(false);
            embedded = create(UUID.randomUUID().toString(), 201);
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "order_db",
                                    "SELECT orchestrator_owner FROM orders WHERE id=" + embedded))
                    .isEqualTo("EMBEDDED");
            E2eSupport.mutateFixture("checkout-saga-service", saga, "start");
            E2eSupport.awaitStatus(external, "CONFIRMED");
            assertThat(
                            E2eSupport.query(
                                    "postgres",
                                    "order_db",
                                    "SELECT orchestrator_owner FROM orders WHERE id=" + external))
                    .isEqualTo("EXTERNAL");
            oneCharge(external);
            oneCharge(embedded);
            E2eSupport.recordSagaEvidence(
                    "M05", external, "external-order-finished-during-admission-rollback");
            E2eSupport.recordSagaEvidence("M05", embedded, "new-order-used-embedded-owner");
        } finally {
            E2eSupport.mutateFixture("checkout-saga-service", saga, "start");
            if (external > 0) cancel(external);
            if (embedded > 0) cancel(embedded);
            E2eSupport.configureAdmission(true);
        }
    }

    private static void oneCharge(long id) {
        assertThat(
                        E2eSupport.query(
                                "postgres",
                                "payment_db",
                                "SELECT count(*) FROM mock_gateway_operations WHERE"
                                        + " request_order_id='"
                                        + id
                                        + "' AND status='COMPLETED'"))
                .isEqualTo("1");
    }
}
