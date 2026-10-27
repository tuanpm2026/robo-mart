package com.robomart.product.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.robomart.outbox.ClaimedEvent;
import com.robomart.outbox.JdbcOutboxStore;

@Repository
public class ProductOutboxStore extends JdbcOutboxStore<ProductOutboxStore.Delivery> {
    public ProductOutboxStore(JdbcTemplate jdbc) {
        super(jdbc, true, (rs, token) -> new Delivery(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5).toInstant(), token, rs.getObject(6, UUID.class)));
    }

    public record Delivery(long id, String aggregateId, String eventType, String payload,
            Instant createdAt, UUID claimToken, UUID eventId) implements ClaimedEvent { }
}
