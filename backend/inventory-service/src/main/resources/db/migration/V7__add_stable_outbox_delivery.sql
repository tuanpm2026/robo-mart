ALTER TABLE outbox_events
    ADD COLUMN event_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMPTZ;
CREATE UNIQUE INDEX idx_inventory_outbox_event_id ON outbox_events(event_id);
CREATE INDEX idx_inventory_outbox_aggregate_pending ON outbox_events(aggregate_type, aggregate_id, id)
    WHERE published=false;
CREATE INDEX idx_inventory_outbox_retention ON outbox_events(published_at) WHERE published=true;

-- stock_restocked never had a published Avro contract or a consumer. Keep legacy records
-- as an audit archive instead of pretending they were delivered or blocking valid events.
CREATE TABLE retired_outbox_events AS
SELECT e.*, now() AS retired_at, 'No external restock event contract; stock_movements retains the audit'::TEXT AS retirement_reason
FROM outbox_events e WHERE false;
ALTER TABLE retired_outbox_events ADD PRIMARY KEY(id);
INSERT INTO retired_outbox_events
SELECT e.*, now(), 'No external restock event contract; stock_movements retains the audit'
FROM outbox_events e WHERE event_type='stock_restocked';
DELETE FROM outbox_events WHERE event_type='stock_restocked';
