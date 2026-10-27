package com.robomart.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Claims only the oldest unpublished event per aggregate, including across replicas. */
public abstract class JdbcOutboxStore<D extends ClaimedEvent> implements OutboxStore<D> {
    private final JdbcTemplate jdbc;

    private final boolean eventIdColumn;
    private final DeliveryMapper<D> mapper;

    protected JdbcOutboxStore(JdbcTemplate jdbc, boolean eventIdColumn, DeliveryMapper<D> mapper) {
        this.jdbc = jdbc;
        this.eventIdColumn = eventIdColumn;
        this.mapper = mapper;
    }

    @FunctionalInterface
    public interface DeliveryMapper<D> {
        D map(java.sql.ResultSet result, UUID claimToken) throws java.sql.SQLException;
    }

    @Transactional
    public List<D> claim(Instant now, Instant until) {
        UUID token = UUID.randomUUID();
        return jdbc.query("WITH candidates AS (SELECT e.id FROM outbox_events e WHERE NOT e.published "
                + "AND (e.claimed_until IS NULL OR e.claimed_until <= ?) AND NOT EXISTS "
                + "(SELECT 1 FROM outbox_events p WHERE p.aggregate_type=e.aggregate_type "
                + "AND p.aggregate_id=e.aggregate_id AND NOT p.published AND p.id < e.id) "
                + "ORDER BY e.id LIMIT 50 FOR UPDATE OF e SKIP LOCKED) "
                + "UPDATE outbox_events e SET claim_token=?, claimed_until=? FROM candidates c WHERE e.id=c.id "
                + "RETURNING e.id, e.aggregate_id, e.event_type, e.payload, e.created_at"
                + (eventIdColumn ? ", e.event_id" : ""),
                (rs, row) -> mapper.map(rs, token), Timestamp.from(now), token, Timestamp.from(until));
    }

    @Transactional
    public boolean acknowledge(D delivery) {
        return jdbc.update("UPDATE outbox_events SET published=true, published_at=?, claim_token=NULL, "
                + "claimed_until=NULL WHERE id=? AND claim_token=? AND NOT published",
                Timestamp.from(Instant.now()), delivery.id(), delivery.claimToken()) == 1;
    }

    @Transactional
    public void release(D delivery) {
        jdbc.update("UPDATE outbox_events SET claim_token=NULL, claimed_until=NULL "
                + "WHERE id=? AND claim_token=? AND NOT published", delivery.id(), delivery.claimToken());
    }

    @Transactional
    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM outbox_events WHERE id IN (SELECT id FROM outbox_events "
                + "WHERE published AND published_at < ? ORDER BY published_at LIMIT 10000)", Timestamp.from(cutoff));
    }

}
