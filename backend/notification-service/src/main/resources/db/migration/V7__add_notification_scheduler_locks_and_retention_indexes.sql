CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
CREATE INDEX idx_processed_events_age ON processed_events(processed_at);
CREATE INDEX idx_failed_event_active_identity ON failed_events(source_consumer_group,source_event_id)
    WHERE status IN ('PENDING','REPLAYING');
