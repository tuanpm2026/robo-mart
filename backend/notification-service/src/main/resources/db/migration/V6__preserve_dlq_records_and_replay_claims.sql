ALTER TABLE failed_events ALTER COLUMN original_topic TYPE VARCHAR(249);
ALTER TABLE failed_events ALTER COLUMN event_type TYPE VARCHAR(249);
ALTER TABLE failed_events ADD COLUMN original_partition INTEGER;
ALTER TABLE failed_events ADD COLUMN original_offset BIGINT;
ALTER TABLE failed_events ADD COLUMN original_timestamp BIGINT;
ALTER TABLE failed_events ADD COLUMN record_key BYTEA;
ALTER TABLE failed_events ADD COLUMN record_value BYTEA;
ALTER TABLE failed_events ADD COLUMN original_headers TEXT;
ALTER TABLE failed_events ADD COLUMN raw_record_available BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE failed_events ADD COLUMN source_event_id VARCHAR(100);
ALTER TABLE failed_events ADD COLUMN source_consumer_group VARCHAR(150);
ALTER TABLE failed_events ADD COLUMN replay_token UUID;
ALTER TABLE failed_events ADD COLUMN replay_claim_until TIMESTAMPTZ;
ALTER TABLE failed_events DROP CONSTRAINT chk_failed_event_status;
-- The old retry path never published anything, and a preview cannot reconstruct the record.
UPDATE failed_events SET status='LEGACY_REVIEW';
ALTER TABLE failed_events ADD CONSTRAINT chk_failed_event_status CHECK
    (status IN ('PENDING','REPLAYING','REPLAYED','LEGACY_REVIEW'));
CREATE UNIQUE INDEX uq_failed_event_source ON failed_events(original_topic,original_partition,original_offset)
    WHERE original_partition IS NOT NULL AND original_offset IS NOT NULL;
CREATE INDEX idx_failed_event_replay_claim ON failed_events(replay_claim_until) WHERE status='REPLAYING';
