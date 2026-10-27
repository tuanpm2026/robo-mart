package com.robomart.product.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Claims only the oldest unpublished event per aggregate, including across replicas. */
@Repository
public class ProductOutboxStore {
    private final JdbcTemplate jdbc;

    public ProductOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<Delivery> claim(Instant now, Instant until) {
        UUID token = UUID.randomUUID();
        return jdbc.query("WITH candidates AS (SELECT e.id FROM outbox_events e WHERE NOT e.published "
                + "AND (e.claimed_until IS NULL OR e.claimed_until <= ?) AND NOT EXISTS "
                + "(SELECT 1 FROM outbox_events p WHERE p.aggregate_type=e.aggregate_type "
                + "AND p.aggregate_id=e.aggregate_id AND NOT p.published AND p.id < e.id) "
                + "ORDER BY e.id LIMIT 50 FOR UPDATE OF e SKIP LOCKED) "
                + "UPDATE outbox_events e SET claim_token=?, claimed_until=? FROM candidates c WHERE e.id=c.id "
                + "RETURNING e.id, e.aggregate_id, e.event_type, e.payload, e.created_at, e.event_id",
                (rs, row) -> new Delivery(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), token, rs.getObject(6, UUID.class)), Timestamp.from(now), token, Timestamp.from(until));
    }

    @Transactional
    public boolean acknowledge(Delivery delivery) {
        return jdbc.update("UPDATE outbox_events SET published=true, published_at=?, claim_token=NULL, "
                + "claimed_until=NULL WHERE id=? AND claim_token=? AND NOT published",
                Timestamp.from(Instant.now()), delivery.id(), delivery.claimToken()) == 1;
    }

    @Transactional
    public void release(Delivery delivery) {
        jdbc.update("UPDATE outbox_events SET claim_token=NULL, claimed_until=NULL "
                + "WHERE id=? AND claim_token=? AND NOT published", delivery.id(), delivery.claimToken());
    }

    @Transactional
    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM outbox_events WHERE id IN (SELECT id FROM outbox_events "
                + "WHERE published AND published_at < ? ORDER BY published_at LIMIT 10000)", Timestamp.from(cutoff));
    }

    public record Delivery(long id, String aggregateId, String eventType, String payload,
            Instant createdAt, UUID claimToken, UUID eventId) { }
}
