-- Existing pending orders remain eligible without rewriting their ledger or history.
ALTER TABLE orders
    ADD COLUMN payment_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_payment_retry_at TIMESTAMPTZ;

CREATE INDEX idx_orders_pending_payment_due
    ON orders (next_payment_retry_at NULLS FIRST, id)
    WHERE status = 'PAYMENT_PENDING';
