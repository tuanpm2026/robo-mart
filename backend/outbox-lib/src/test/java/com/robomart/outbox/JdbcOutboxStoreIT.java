package com.robomart.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual PostgreSQL semantics shared by all three owning-service adapters. */
@Testcontainers
class JdbcOutboxStoreIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static JdbcTemplate jdbc;
    private static Store first;
    private static Store second;

    record Delivery(long id, String eventType, UUID claimToken, UUID eventId) implements ClaimedEvent { }

    static class Store extends JdbcOutboxStore<Delivery> {
        Store(boolean eventIdColumn) {
            super(jdbc, eventIdColumn, (rs, token) -> new Delivery(rs.getLong(1), rs.getString(3), token,
                    eventIdColumn ? rs.getObject(6, UUID.class) : null));
        }
    }

    @BeforeAll
    static void connect() {
        var data = new PGSimpleDataSource();
        data.setURL(POSTGRES.getJdbcUrl());
        data.setUser(POSTGRES.getUsername());
        data.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE outbox_events(id BIGSERIAL PRIMARY KEY, aggregate_type text NOT NULL, "
                + "aggregate_id text NOT NULL, event_type text NOT NULL, payload text NOT NULL, "
                + "created_at timestamptz NOT NULL DEFAULT now(), event_id uuid NOT NULL, "
                + "published boolean NOT NULL DEFAULT false, published_at timestamptz, "
                + "claim_token uuid, claimed_until timestamptz)");
        first = new Store(true);
        second = new Store(true);
    }

    @BeforeEach
    void clear() { jdbc.execute("TRUNCATE outbox_events RESTART IDENTITY"); }

    private long add(String aggregate, String eventType) {
        return jdbc.queryForObject("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,event_id) "
                + "VALUES ('item',?,?, '{}',?) RETURNING id", Long.class, aggregate, eventType, UUID.randomUUID());
    }

    @Test
    void concurrentReplicasClaimDistinctHeadsAndCannotSkipAnUnknownHead() throws Exception {
        long a1 = add("a", "unknown");
        long a2 = add("a", "known");
        long b = add("b", "known");
        Instant now = Instant.now();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> first.claim(now, now.plusSeconds(60)));
            var two = executor.submit(() -> second.claim(now, now.plusSeconds(60)));
            var claimed = new java.util.ArrayList<>(one.get(5, TimeUnit.SECONDS));
            claimed.addAll(two.get(5, TimeUnit.SECONDS));
            assertThat(claimed).extracting(Delivery::id).containsExactlyInAnyOrder(a1, b);
            var unknown = claimed.stream().filter(d -> d.id() == a1).findFirst().orElseThrow();
            var independent = claimed.stream().filter(d -> d.id() == b).findFirst().orElseThrow();
            first.release(unknown);
            assertThat(second.acknowledge(independent)).isTrue();
            var retry = second.claim(now, now.plusSeconds(60));
            assertThat(retry).extracting(Delivery::id).containsExactly(a1).doesNotContain(a2);
            assertThat(retry.getFirst().eventId()).isEqualTo(unknown.eventId());
            assertThat(second.acknowledge(retry.getFirst())).isTrue();
            assertThat(first.claim(now, now.plusSeconds(60))).extracting(Delivery::id).containsExactly(a2);
        }
    }

    @Test
    void skipsALockedAggregateHeadWithoutWaitingOrClaimingItsSuccessor() throws Exception {
        long lockedHead = add("locked", "known");
        long successor = add("locked", "known");
        long independent = add("free", "known");
        try (var executor = Executors.newSingleThreadExecutor();
                var lock = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            lock.setAutoCommit(false);
            try {
                try (var statement = lock.prepareStatement("SELECT id FROM outbox_events WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, lockedHead);
                    statement.executeQuery().close();
                }
                var claimed = executor.submit(() -> first.claim(Instant.now(), Instant.now().plusSeconds(60)))
                        .get(3, TimeUnit.SECONDS);
                assertThat(claimed).extracting(Delivery::id).containsExactly(independent)
                        .doesNotContain(lockedHead, successor);
            } finally {
                lock.rollback();
            }
        }
    }

    @Test
    void expiryChangesOnlyTheFenceAndRejectsAnOldAcknowledgementOrRelease() {
        long id = add("a", "known");
        Instant now = Instant.now();
        var old = first.claim(now, now.plusMillis(1)).getFirst();
        var fresh = second.claim(now.plusSeconds(1), now.plusSeconds(60)).getFirst();
        assertThat(fresh.id()).isEqualTo(id);
        assertThat(fresh.eventId()).isEqualTo(old.eventId());
        assertThat(fresh.claimToken()).isNotEqualTo(old.claimToken());
        assertThat(first.acknowledge(old)).isFalse();
        first.release(old);
        assertThat(jdbc.queryForObject("SELECT claim_token FROM outbox_events WHERE id=?", UUID.class, id))
                .isEqualTo(fresh.claimToken());
        assertThat(second.acknowledge(fresh)).isTrue();
        assertThat(first.acknowledge(fresh)).isFalse();
        assertThat(first.claim(now.plusSeconds(2), now.plusSeconds(60))).isEmpty();
    }

    @Test
    void retentionDeletesOnlyDeliveredRowsOlderThanTheCutoff() {
        long old = add("old", "known");
        long recent = add("recent", "known");
        long unpublished = add("unpublished", "known");
        jdbc.update("UPDATE outbox_events SET published=true,published_at=now()-interval '8 days' WHERE id=?", old);
        jdbc.update("UPDATE outbox_events SET published=true,published_at=now() WHERE id=?", recent);
        jdbc.update("UPDATE outbox_events SET created_at=now()-interval '9 days' WHERE id=?", unpublished);
        assertThat(first.deletePublishedBefore(Instant.now().minus(Duration.ofDays(7)))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM outbox_events ORDER BY id", Long.class))
                .containsExactly(recent, unpublished);
    }

    @Test
    void retentionIsBoundedToTenThousandDeliveredRowsPerPass() {
        jdbc.execute("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,event_id,published,published_at) "
                + "SELECT 'item',id::text,'known','{}',gen_random_uuid(),true,now()-interval '8 days' "
                + "FROM generate_series(1,10001) id");
        long unpublished = add("unpublished", "known");
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));
        assertThat(first.deletePublishedBefore(cutoff)).isEqualTo(10000);
        assertThat(first.deletePublishedBefore(cutoff)).isEqualTo(1);
        assertThat(first.deletePublishedBefore(cutoff)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM outbox_events", Long.class)).containsExactly(unpublished);
    }

    @Test
    void legacyRowDerivedIdentityAdapterDoesNotRequireAnEventIdColumn() {
        long id = add("legacy", "known");
        jdbc.execute("ALTER TABLE outbox_events RENAME COLUMN event_id TO hidden_event_id");
        try {
            var legacy = new Store(false);
            var row = legacy.claim(Instant.now(), Instant.now().plusSeconds(60)).getFirst();
            assertThat(row.id()).isEqualTo(id);
            assertThat(row.eventId()).isNull();
            assertThat(legacy.acknowledge(row)).isTrue();
        } finally {
            jdbc.execute("ALTER TABLE outbox_events RENAME COLUMN hidden_event_id TO event_id");
        }
    }
}
