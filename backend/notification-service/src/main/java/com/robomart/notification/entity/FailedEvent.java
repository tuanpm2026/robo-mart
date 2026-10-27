package com.robomart.notification.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "failed_events")
public class FailedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 249)
    private String eventType;

    @Column(name = "aggregate_id", length = 200)
    private String aggregateId;

    @Column(name = "original_topic", nullable = false, length = 249)
    private String originalTopic;

    @Column(name = "error_class", length = 500)
    private String errorClass;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "payload_preview", columnDefinition = "TEXT")
    private String payloadPreview;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "first_failed_at", nullable = false, updatable = false)
    private Instant firstFailedAt;

    @Column(name = "last_attempted_at", nullable = false)
    private Instant lastAttemptedAt;

    @Column(name = "original_partition")
    private Integer originalPartition;

    @Column(name = "original_offset")
    private Long originalOffset;

    @Column(name = "original_timestamp")
    private Long originalTimestamp;

    @Column(name = "record_key", columnDefinition = "bytea")
    private byte[] recordKey;

    @Column(name = "record_value", columnDefinition = "bytea")
    private byte[] recordValue;

    @Column(name = "original_headers", columnDefinition = "TEXT")
    private String originalHeaders;

    @Column(name = "raw_record_available")
    private boolean rawRecordAvailable;

    @Column(name = "source_event_id", length = 100)
    private String sourceEventId;

    @Column(name = "source_consumer_group", length = 150)
    private String sourceConsumerGroup;

    @Column(name = "replay_token")
    private UUID replayToken;

    @Column(name = "replay_claim_until")
    private Instant replayClaimUntil;

    public Integer getOriginalPartition() { return originalPartition; }
    public void setOriginalPartition(Integer value) { originalPartition = value; }
    public Long getOriginalOffset() { return originalOffset; }
    public void setOriginalOffset(Long value) { originalOffset = value; }
    public Long getOriginalTimestamp() { return originalTimestamp; }
    public void setOriginalTimestamp(Long value) { originalTimestamp = value; }
    public byte[] getRecordKey() { return recordKey; }
    public void setRecordKey(byte[] value) { recordKey = value; }
    public byte[] getRecordValue() { return recordValue; }
    public void setRecordValue(byte[] value) { recordValue = value; }
    public String getOriginalHeaders() { return originalHeaders; }
    public void setOriginalHeaders(String value) { originalHeaders = value; }
    public boolean isRawRecordAvailable() { return rawRecordAvailable; }
    public void setRawRecordAvailable(boolean value) { rawRecordAvailable = value; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String value) { sourceEventId = value; }
    public String getSourceConsumerGroup() { return sourceConsumerGroup; }
    public void setSourceConsumerGroup(String value) { sourceConsumerGroup = value; }
    public UUID getReplayToken() { return replayToken; }
    public void setReplayToken(UUID value) { replayToken = value; }
    public Instant getReplayClaimUntil() { return replayClaimUntil; }
    public void setReplayClaimUntil(Instant value) { replayClaimUntil = value; }

    @PrePersist
    protected void onCreate() {
        this.firstFailedAt = Instant.now();
        this.lastAttemptedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getOriginalTopic() {
        return originalTopic;
    }

    public void setOriginalTopic(String originalTopic) {
        this.originalTopic = originalTopic;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(String errorClass) {
        this.errorClass = errorClass;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getPayloadPreview() {
        return payloadPreview;
    }

    public void setPayloadPreview(String payloadPreview) {
        this.payloadPreview = payloadPreview;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }

    public void setFirstFailedAt(Instant firstFailedAt) {
        this.firstFailedAt = firstFailedAt;
    }

    public Instant getLastAttemptedAt() {
        return lastAttemptedAt;
    }

    public void setLastAttemptedAt(Instant lastAttemptedAt) {
        this.lastAttemptedAt = lastAttemptedAt;
    }
}
