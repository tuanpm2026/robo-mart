ALTER TABLE outbox_events ADD COLUMN claim_token UUID, ADD COLUMN claimed_until TIMESTAMPTZ;
CREATE INDEX idx_order_outbox_aggregate_pending ON outbox_events(aggregate_type, aggregate_id, id)
    WHERE published = false;
CREATE INDEX idx_order_outbox_retention ON outbox_events(published_at) WHERE published = true;
