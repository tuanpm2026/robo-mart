CREATE TABLE failed_events (
    id               BIGSERIAL PRIMARY KEY,
    event_type       VARCHAR(200) NOT NULL,
    aggregate_id     VARCHAR(200),
    original_topic   VARCHAR(200) NOT NULL,
    error_class      VARCHAR(500),
    error_message    TEXT,
    payload_preview  TEXT,
    retry_count      INTEGER NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    first_failed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_attempted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_failed_event_status CHECK (status IN ('PENDING', 'RESOLVED', 'FAILED_RETRY'))
);

CREATE INDEX idx_failed_events_status ON failed_events(status);
CREATE INDEX idx_failed_events_first_failed_at ON failed_events(first_failed_at DESC);
