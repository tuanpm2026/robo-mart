package com.robomart.saga;

import static org.assertj.core.api.Assertions.*;

import com.robomart.common.saga.CheckoutSnapshot;
import com.robomart.events.order.CheckoutSagaRequestedEvent;
import com.robomart.test.PostgresContainerConfig;
import com.robomart.test.security.TestJwtDecoderConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "saga.identity.client-secret=test-only",
            "saga.worker.enabled=false",
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.kafka.listener.auto-startup=false",
            "management.endpoint.health.validate-group-membership=false"
        })
@Import({PostgresContainerConfig.class, TestJwtDecoderConfig.class})
class SagaStoreIT {
    @Autowired SagaStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired SagaWorker worker;
    @Autowired SagaMetrics metrics;
    @MockitoBean SagaParticipants participants;
    UUID id;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE saga_operator_audit,saga_steps,saga_inbox,saga_instances");
        id = UUID.randomUUID();
    }

    CheckoutSagaRequestedEvent event(String type, long sequence) {
        String json =
                mapper.writeValueAsString(
                        new CheckoutSnapshot(
                                "42",
                                id.toString(),
                                1,
                                "user",
                                "20.00",
                                "USD",
                                1,
                                List.of(new CheckoutSnapshot.Line("1", 2))));
        return CheckoutSagaRequestedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setRequestId(id + ":" + type)
                .setRequestType(type)
                .setRequestSequence(sequence)
                .setOrderId("42")
                .setSagaId(id.toString())
                .setOwnerEpoch(1)
                .setWorkflowVersion(1)
                .setSnapshotJson(json)
                .setReason("CANCEL".equals(type) ? "Customer request" : "")
                .build();
    }

    @Test
    void blockedParticipantDoesNotHoldConnectionsAndStaleWorkerCannotAdvance() throws Exception {
        store.accept(event("START", 1));
        Work initial = store.claim(1, Duration.ofSeconds(30)).getFirst();
        store.advance(initial, Transition.cancel(initial, "test"));
        Work old = store.claim(1, Duration.ofSeconds(30)).getFirst();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(participants.command(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            entered.countDown();
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Barrier timed out");
                            return com.robomart.proto.saga.OrderWorkflowResult.newBuilder()
                                    .setApplied(true)
                                    .build();
                        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = pool.submit(() -> worker.process(old));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                try (var a = jdbc.getDataSource().getConnection();
                        var b = jdbc.getDataSource().getConnection()) {
                    assertThat(a.isValid(1) && b.isValid(1)).isTrue();
                }
                jdbc.update("UPDATE saga_instances SET claim_until=now()-interval '1 second'");
                assertThat(store.claim(1, Duration.ofSeconds(30))).hasSize(1);
            } finally {
                release.countDown();
            }
            task.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT state FROM saga_instances", String.class))
                .isEqualTo("BEGIN_CANCEL");
    }

    @Test
    void adminInspectionAndResumeEnforceRoleAndAuditReason() {
        store.accept(event("START", 1));
        Work w = store.claim(1, Duration.ofSeconds(30)).getFirst();
        store.retry(w, "unknown", true, Duration.ZERO);
        var client =
                org.springframework.web.client.RestClient.builder()
                        .baseUrl("http://localhost:" + port)
                        .defaultStatusHandler(status -> status.isError(), (q, a) -> {})
                        .build();
        assertThat(
                        client.get()
                                .uri("/api/v1/admin/sagas")
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(
                        client.get()
                                .uri("/api/v1/admin/sagas")
                                .header(
                                        "Authorization",
                                        com.robomart.test.security.TestJwt.customerBearer())
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        client.get()
                                .uri("/api/v1/admin/sagas")
                                .header(
                                        "Authorization",
                                        com.robomart.test.security.TestJwt.adminBearer())
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        client.post()
                                .uri("/api/v1/admin/sagas/" + id + "/resume")
                                .header(
                                        "Authorization",
                                        com.robomart.test.security.TestJwt.adminBearer())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .body(java.util.Map.of("reason", "Provider recovered"))
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .value())
                .isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT reason FROM saga_operator_audit", String.class))
                .isEqualTo("Provider recovered");
        metrics.refresh();
    }

    @Test
    void failureAfterWorkflowInsertRollsBackAdmissionAndRedeliverySucceeds() {
        var start = event("START", 1);
        jdbc.execute("ALTER TABLE saga_inbox ADD CONSTRAINT test_inbox_failure CHECK (false)");
        try {
            assertThatThrownBy(() -> store.accept(start))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_instances", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_inbox", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE saga_inbox DROP CONSTRAINT test_inbox_failure");
        }
        store.accept(start);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_instances", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_inbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void statelessApiRejectsAmbientCredentialsAndRetainsCsrfElsewhere() {
        var client =
                org.springframework.web.client.RestClient.builder()
                        .baseUrl("http://localhost:" + port)
                        .defaultStatusHandler(status -> status.isError(), (request, response) -> {})
                        .build();
        for (var headers :
                List.of(
                        java.util.Map.<String, String>of(),
                        java.util.Map.of(
                                "Cookie",
                                "access_token="
                                        + com.robomart.test.security.TestJwt.ADMIN_TOKEN
                                        + "; JSESSIONID=ignored"),
                        java.util.Map.of("Authorization", "Basic YWRtaW46cGFzc3dvcmQ="))) {
            assertThat(
                            client.post()
                                    .uri("/api/v1/admin/sagas/" + id + "/resume")
                                    .headers(h -> headers.forEach(h::set))
                                    .contentType(
                                            org.springframework.http.MediaType.APPLICATION_JSON)
                                    .body(java.util.Map.of("reason", "test"))
                                    .retrieve()
                                    .toBodilessEntity()
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(401);
        }
        assertThat(
                        client.get()
                                .uri("/actuator/health")
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .value())
                .isIn(200, 503);
        assertThat(
                        client.post()
                                .uri("/actuator/health")
                                .header(
                                        "Cookie",
                                        "access_token="
                                                + com.robomart.test.security.TestJwt.ADMIN_TOKEN)
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .value())
                .isIn(401, 403);
    }

    @Test
    void duplicateAndCancelBeforeStartRemainOneWorkflow() {
        var cancel = event("CANCEL", 2);
        store.accept(cancel);
        store.accept(cancel);
        store.accept(event("START", 1));
        Work w = store.claim(1, Duration.ofSeconds(30)).getFirst();
        assertThat(w.cancelRequested()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_instances", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_sequence FROM saga_instances", Long.class))
                .isEqualTo(2);
    }

    @Test
    void conflictingReplayRollsBackInboxAndWorkflow() {
        var start = event("START", 1);
        store.accept(start);
        start.setReason("changed");
        assertThatThrownBy(() -> store.accept(start)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_inbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void twoWorkersAndExpiredLeaseAreFenced() throws Exception {
        store.accept(event("START", 1));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> store.claim(1, Duration.ofSeconds(30)));
            var b = pool.submit(() -> store.claim(1, Duration.ofSeconds(30)));
            assertThat(a.get(5, TimeUnit.SECONDS).size() + b.get(5, TimeUnit.SECONDS).size())
                    .isEqualTo(1);
        }
        jdbc.update("UPDATE saga_instances SET claim_until=now()-interval '1 second'");
        Work stale = store.claim(1, Duration.ofSeconds(30)).getFirst();
        jdbc.update("UPDATE saga_instances SET claim_until=now()-interval '1 second'");
        Work fresh = store.claim(1, Duration.ofSeconds(30)).getFirst();
        assertThat(store.intent(stale, "charge", new byte[] {1})).isFalse();
        assertThat(store.advance(stale, Transition.next(stale, "CONFIRM"))).isFalse();
        assertThat(store.intent(fresh, "charge", new byte[] {1})).isTrue();
    }

    @Test
    void immutableStepIntentSurvivesReplay() {
        store.accept(event("START", 1));
        Work w = store.claim(1, Duration.ofSeconds(30)).getFirst();
        assertThat(store.intent(w, "charge", new byte[] {1})).isTrue();
        assertThat(store.intent(w, "charge", new byte[] {1})).isTrue();
        assertThatThrownBy(() -> store.intent(w, "charge", new byte[] {2}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelArrivingDuringForwardCallWinsNextTransition() {
        store.accept(event("START", 1));
        Work w = store.claim(1, Duration.ofSeconds(30)).getFirst();
        store.accept(event("CANCEL", 2));
        store.advance(w, new Transition("CONFIRM", "res", "9", "SUCCEEDED", ""));
        Work cancel = store.claim(1, Duration.ofSeconds(30)).getFirst();
        assertThat(cancel.state()).isEqualTo("BEGIN_CANCEL");
        assertThat(cancel.paymentId()).isEqualTo("9");
    }

    @Test
    void confirmedWorkflowStaysAvailableForLaterCancellation() {
        store.accept(event("START", 1));
        Work w = store.claim(1, Duration.ofSeconds(30)).getFirst();
        store.advance(w, Transition.next(w, "CHECKOUT_CONFIRMED"));
        assertThat(store.claim(1, Duration.ofSeconds(30))).isEmpty();
        store.accept(event("CANCEL", 2));
        assertThat(store.claim(1, Duration.ofSeconds(30)).getFirst().cancelRequested()).isTrue();
    }

    @Test
    void manualReviewRequiresAuditedResumeAndKeepsOperationIdentity() {
        store.accept(event("START", 1));
        Work w = store.claim(1, Duration.ofSeconds(30)).getFirst();
        store.intent(w, "charge", new byte[] {1});
        store.retry(w, "unresolved", true, Duration.ZERO);
        store.accept(event("CANCEL", 2));
        assertThat(store.claim(1, Duration.ofSeconds(30))).isEmpty();
        store.resume(id, "operator", "Provider restored");
        Work resumed = store.claim(1, Duration.ofSeconds(30)).getFirst();
        assertThat(resumed.attempts()).isEqualTo(1);
        assertThat(store.intent(resumed, "charge", new byte[] {1})).isTrue();
        assertThat(jdbc.queryForObject("SELECT actor FROM saga_operator_audit", String.class))
                .isEqualTo("operator");
    }
}
