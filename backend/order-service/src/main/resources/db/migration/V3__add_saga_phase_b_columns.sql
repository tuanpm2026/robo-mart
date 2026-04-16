-- Phase B: Hardened Saga Orchestration columns for idempotency, timeouts, and dead saga detection

ALTER TABLE saga_audit_log
    ADD COLUMN idempotency_key VARCHAR(200),
    ADD COLUMN timeout_at      TIMESTAMPTZ,
    ADD COLUMN retry_count     INTEGER NOT NULL DEFAULT 0;

-- Unique constraint to enforce idempotency at DB level (closes TOCTOU race in application layer)
ALTER TABLE saga_audit_log
    ADD CONSTRAINT uq_saga_audit_log_idempotency_key
    UNIQUE (idempotency_key);

-- Partial index for fast idempotency lookups (only SUCCESS records matter)
CREATE INDEX idx_saga_audit_log_idempotency
    ON saga_audit_log(idempotency_key)
    WHERE status = 'SUCCESS' AND idempotency_key IS NOT NULL;

-- Composite index for dead saga detection queries (status + updated_at)
-- updated_at already exists from V1 migration; index is new
CREATE INDEX idx_orders_status_updated_at ON orders(status, updated_at);
