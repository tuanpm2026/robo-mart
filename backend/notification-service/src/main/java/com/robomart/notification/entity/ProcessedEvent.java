package com.robomart.notification.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.CreationTimestamp;

/**
 * Records that a Kafka event (identified by {@code eventId}) has been handled by a given consumer
 * group, so at-least-once redelivery does not reprocess it (duplicate notifications / pushes). The
 * unique {@code (consumer_group, event_id)} constraint is the idempotency key — keyed per group so
 * the same event consumed by independent consumer groups is processed once each.
 */
@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_processed_events_group_event",
                columnNames = {"consumer_group", "event_id"}))
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_group", nullable = false, length = 150)
    private String consumerGroup;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String consumerGroup, String eventId) {
        this.consumerGroup = consumerGroup;
        this.eventId = eventId;
    }

    public Long getId() {
        return id;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
