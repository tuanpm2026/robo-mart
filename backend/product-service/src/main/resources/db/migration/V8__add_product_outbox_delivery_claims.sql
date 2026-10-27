ALTER TABLE outbox_events
    ADD COLUMN event_id UUID,
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMPTZ;

-- Preserve the exact UUID.nameUUIDFromBytes("product-outbox-" + id) identity used before V8.
-- MD5 name UUIDs set the version/variant bits after hashing; a plain md5(...)::uuid differs.
WITH hashes AS (
    SELECT id, decode(md5('product-outbox-' || id), 'hex') AS bytes FROM outbox_events
)
UPDATE outbox_events e SET event_id = encode(
    set_byte(set_byte(h.bytes, 6, (get_byte(h.bytes, 6) & 15) | 48),
        8, (get_byte(h.bytes, 8) & 63) | 128), 'hex')::uuid
FROM hashes h WHERE e.id=h.id;
ALTER TABLE outbox_events ALTER COLUMN event_id SET DEFAULT gen_random_uuid();
ALTER TABLE outbox_events ALTER COLUMN event_id SET NOT NULL;
CREATE UNIQUE INDEX idx_product_outbox_event_id ON outbox_events(event_id);
CREATE INDEX idx_product_outbox_aggregate_pending ON outbox_events(aggregate_type,aggregate_id,id)
    WHERE published=false;
CREATE INDEX idx_product_outbox_retention ON outbox_events(published_at) WHERE published=true;

CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
