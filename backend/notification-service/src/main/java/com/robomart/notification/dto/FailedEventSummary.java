package com.robomart.notification.dto;

import java.time.Instant;

/** Closed projection keeps large raw record bodies out of paged admin lists. */
public interface FailedEventSummary {
    Long getId();
    String getEventType();
    String getAggregateId();
    String getOriginalTopic();
    String getErrorClass();
    String getErrorMessage();
    String getPayloadPreview();
    int getRetryCount();
    String getStatus();
    Instant getFirstFailedAt();
    Instant getLastAttemptedAt();
    boolean isRawRecordAvailable();
    Instant getReplayClaimUntil();
}
