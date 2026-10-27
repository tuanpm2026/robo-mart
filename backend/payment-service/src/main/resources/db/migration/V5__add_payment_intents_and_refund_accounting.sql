ALTER TABLE payments ADD COLUMN processing_token UUID;
ALTER TABLE payments ADD COLUMN processing_until TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN refunded_amount NUMERIC(19,2) DEFAULT 0;
-- Provider receipts are authoritative. Historical REFUNDED rows without one remain unknown,
-- and cannot be refunded again until an operator reconciles the original provider receipt.
UPDATE payments p SET refunded_amount = (
    SELECT g.amount FROM mock_gateway_operations g
    WHERE g.operation_key = 'refund:' || p.transaction_id AND g.status = 'REFUNDED'
) WHERE p.status = 'REFUNDED';
ALTER TABLE payments ADD CONSTRAINT chk_refunded_amount CHECK (
    (refunded_amount IS NOT NULL AND refunded_amount >= 0 AND refunded_amount <= amount)
    OR (refunded_amount IS NULL AND status = 'REFUNDED'));

CREATE TABLE refund_intents (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id),
    idempotency_key VARCHAR(100) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    processing_token UUID,
    processing_until TIMESTAMPTZ,
    transaction_id VARCHAR(100),
    CONSTRAINT uq_refund_request UNIQUE (payment_id, idempotency_key)
);
CREATE INDEX idx_refund_intents_pending ON refund_intents(payment_id) WHERE status='PENDING';

-- No service consumes these financial outbox types. Preserve history without a dead publisher.
ALTER TABLE outbox_events RENAME TO retired_outbox_events;
