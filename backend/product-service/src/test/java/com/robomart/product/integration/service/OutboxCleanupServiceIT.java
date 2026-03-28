package com.robomart.product.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.robomart.product.entity.OutboxEvent;
import com.robomart.product.repository.OutboxEventRepository;
import com.robomart.product.service.OutboxCleanupService;
import com.robomart.test.IntegrationTest;

@IntegrationTest
class OutboxCleanupServiceIT {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxCleanupService outboxCleanupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
    }

    @Test
    void shouldDeleteOldPublishedEvents() {
        // Insert old published event via SQL (to set published_at in the past)
        jdbcTemplate.update(
                "INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, created_at, published, published_at) " +
                "VALUES (?, ?, ?, ?::jsonb, NOW() - INTERVAL '10 days', true, NOW() - INTERVAL '10 days')",
                "PRODUCT", "old-1", "PRODUCT_CREATED", "{\"id\":999}"
        );

        // Insert recent published event
        var recentEvent = new OutboxEvent("PRODUCT", "recent-1", "PRODUCT_CREATED", "{\"id\":998}");
        recentEvent.markPublished();
        outboxEventRepository.save(recentEvent);

        // Insert unpublished event
        outboxEventRepository.save(new OutboxEvent("PRODUCT", "unpub-1", "PRODUCT_CREATED", "{\"id\":997}"));

        outboxCleanupService.cleanupOldEvents();

        List<OutboxEvent> remaining = outboxEventRepository.findAll();
        assertThat(remaining).hasSize(2);
        assertThat(remaining).noneMatch(e -> "old-1".equals(e.getAggregateId()));
    }
}
