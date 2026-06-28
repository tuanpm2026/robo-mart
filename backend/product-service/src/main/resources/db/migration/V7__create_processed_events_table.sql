-- Consumer-side idempotency: records which Kafka events each consumer group has handled, so
-- at-least-once redelivery does not trigger redundant Elasticsearch re-indexing or cache eviction.
-- The (consumer_group, event_id) unique constraint is the dedup key.
CREATE TABLE processed_events (
    id             BIGSERIAL PRIMARY KEY,
    consumer_group VARCHAR(150) NOT NULL,
    event_id       VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_processed_events_group_event UNIQUE (consumer_group, event_id)
);
