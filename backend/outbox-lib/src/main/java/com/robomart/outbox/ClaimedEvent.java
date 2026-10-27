package com.robomart.outbox;

import java.util.UUID;

/** A fenced delivery handle; event payload mapping remains owned by the service. */
public interface ClaimedEvent {
    long id();
    String eventType();
    UUID claimToken();
}
