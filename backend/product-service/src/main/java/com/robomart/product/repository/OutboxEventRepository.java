package com.robomart.product.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.product.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByPublishedFalseOrderByCreatedAtAsc();

    @Query(value = "SELECT * FROM outbox_events WHERE published = false ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findUnpublishedSkipLocked(@Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.published = true AND e.publishedAt < :cutoff")
    int deleteByPublishedTrueAndPublishedAtBefore(Instant cutoff);
}
