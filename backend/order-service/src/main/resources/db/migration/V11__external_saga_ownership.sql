ALTER TABLE orders ADD COLUMN orchestrator_owner VARCHAR(16) NOT NULL DEFAULT 'EMBEDDED'
    CHECK (orchestrator_owner IN ('EMBEDDED', 'EXTERNAL'));
ALTER TABLE orders ADD COLUMN orchestrator_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN saga_id UUID;
ALTER TABLE orders ADD COLUMN request_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE orders ADD COLUMN checkout_snapshot TEXT;
CREATE UNIQUE INDEX idx_orders_saga_identity ON orders(saga_id) WHERE saga_id IS NOT NULL;
CREATE TABLE order_workflow_receipts (
    command_id VARCHAR(200) PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    owner_epoch BIGINT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    applied BOOLEAN NOT NULL,
    result_status VARCHAR(50) NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_workflow_receipts_order ON order_workflow_receipts(order_id);
