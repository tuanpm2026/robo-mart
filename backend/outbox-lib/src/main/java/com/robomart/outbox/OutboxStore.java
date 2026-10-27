package com.robomart.outbox;

import java.time.Instant;
import java.util.List;

public interface OutboxStore<D extends ClaimedEvent> {
    List<D> claim(Instant now, Instant until);
    boolean acknowledge(D delivery);
    void release(D delivery);
    int deletePublishedBefore(Instant cutoff);
}
