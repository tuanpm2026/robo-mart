ALTER TABLE reservations ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '24 hours');
UPDATE reservations SET expires_at=created_at + interval '24 hours';
CREATE INDEX idx_inventory_expired_reservations ON reservations(expires_at, id) WHERE status='RESERVED';
CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(consumer_group, event_id)
);
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
