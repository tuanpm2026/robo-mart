package com.robomart.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.robomart.order.event.producer.OrderEventProducer;
import com.robomart.order.service.OrderOutboxStore.Delivery;
import com.robomart.order.service.OrderOutboxStore;
import com.robomart.order.service.OutboxPollingService;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "saga.payment-recovery.enabled=false", "saga.dead-saga-detection.initial-delay-ms=3600000",
        "outbox.initial-delay-ms=3600000", "outbox.publish-timeout=2s"})
@ActiveProfiles("test")
@Import(OrderOutboxDeliveryIT.Database.class)
class OrderOutboxDeliveryIT {
    @MockitoBean private OrderEventProducer producer;
    @Autowired private OrderOutboxStore store;
    @Autowired private OutboxPollingService poller;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MeterRegistry meters;

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> outboxPostgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("order_outbox_test");
        }
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM outbox_events");
        reset(producer);
    }

    private long event(String aggregate, String type) {
        return jdbc.queryForObject("INSERT INTO outbox_events(aggregate_type, aggregate_id, event_type, payload, "
                + "created_at, published) VALUES ('Order', ?, ?, cast(? as jsonb), now(), false) RETURNING id",
                Long.class, aggregate, type, "{\"orderId\":\"" + aggregate
                        + "\",\"previousStatus\":\"PAYMENT_PROCESSING\",\"newStatus\":\"CONFIRMED\"}");
    }

    private List<Delivery> claim() {
        return store.claim(Instant.now(), Instant.now().plusSeconds(120));
    }

    private boolean published(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT published FROM outbox_events WHERE id=?",
                Boolean.class, id));
    }

    @Test
    void leasedHeadBlocksLaterRowsEvenForAnotherReplica() {
        long first = event("a", "order_status_changed");
        long second = event("a", "order_status_changed");
        long independent = event("b", "order_status_changed");
        List<Delivery> firstBatch = claim();
        assertThat(firstBatch).extracting(Delivery::id).containsExactlyInAnyOrder(first, independent);
        assertThat(claim()).isEmpty();
        store.acknowledge(firstBatch.stream().filter(row -> row.id() == first).findFirst().orElseThrow());
        assertThat(claim()).extracting(Delivery::id).containsExactly(second);
    }

    @Test
    void concurrentReplicasClaimEachHeadAtMostOnce() throws Exception {
        for (int i = 0; i < 10; i++) {
            event("aggregate-" + i, "order_status_changed");
            event("aggregate-" + i, "order_status_changed");
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<List<Delivery>>> batches = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                batches.add(pool.submit(() -> {
                    start.await();
                    return claim();
                }));
            }
            start.countDown();
            Set<Long> ids = new HashSet<>();
            Set<String> aggregates = new HashSet<>();
            for (var batch : batches) {
                for (var delivery : batch.get(10, TimeUnit.SECONDS)) {
                    assertThat(ids.add(delivery.id())).isTrue();
                    assertThat(aggregates.add(delivery.aggregateId())).isTrue();
                }
            }
            assertThat(ids).hasSize(10);
        }
    }

    @Test
    void expiredWorkerCannotAcknowledgeOrReleaseNewOwnersClaim() {
        event("a", "order_status_changed");
        Delivery old = claim().getFirst();
        jdbc.update("UPDATE outbox_events SET claimed_until=now() - interval '1 minute'");
        Delivery current = claim().getFirst();
        assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
        assertThat(store.acknowledge(old)).isFalse();
        store.release(old);
        assertThat(claim()).isEmpty();
        assertThat(store.acknowledge(current)).isTrue();
    }

    @Test
    void publishingHoldsNoTransactionOrRowLock() {
        long id = event("a", "order_status_changed");
        when(producer.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForList("SELECT id FROM outbox_events WHERE id=? FOR UPDATE NOWAIT", Long.class, id))
                    .containsExactly(id);
            return CompletableFuture.completedFuture(null);
        });
        poller.pollAndPublish();
        assertThat(published(id)).isTrue();
    }

    @Test
    void poisonHeadIsRetainedAndBlocksItsAggregateWhileOtherAggregatesPublish() {
        long poison = event("poison", "unknown-type");
        long later = event("poison", "order_status_changed");
        long good = event("good", "order_status_changed");
        when(producer.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        double before = meters.counter("robomart.outbox.unmappable").count();
        poller.pollAndPublish();
        assertThat(published(poison)).isFalse();
        assertThat(published(later)).isFalse();
        assertThat(published(good)).isTrue();
        assertThat(meters.counter("robomart.outbox.unmappable").count()).isEqualTo(before + 1);
        assertThat(claim()).extracting(Delivery::id).containsExactly(poison);
    }

    @Test
    void rollbackRestoresClaimEligibility() {
        long id = event("a", "order_status_changed");
        transactions.execute(status -> {
            assertThat(claim()).extracting(Delivery::id).containsExactly(id);
            status.setRollbackOnly();
            return null;
        });
        assertThat(claim()).extracting(Delivery::id).containsExactly(id);
    }

    @Test
    void retentionDeletesOnlyOldPublishedRows() {
        long oldPublished = event("a", "order_status_changed");
        long oldPending = event("b", "order_status_changed");
        long recentPublished = event("c", "order_status_changed");
        jdbc.update("UPDATE outbox_events SET published=true, published_at=now() - interval '8 days' WHERE id=?",
                oldPublished);
        jdbc.update("UPDATE outbox_events SET created_at=now() - interval '8 days' WHERE id=?", oldPending);
        jdbc.update("UPDATE outbox_events SET published=true, published_at=now() WHERE id=?", recentPublished);
        assertThat(store.deletePublishedBefore(Instant.now().minusSeconds(7 * 86400))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM outbox_events ORDER BY id", Long.class))
                .containsExactly(oldPending, recentPublished);
    }
}
