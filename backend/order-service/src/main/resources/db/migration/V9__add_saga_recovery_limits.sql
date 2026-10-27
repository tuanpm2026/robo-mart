CREATE TABLE saga_recovery_state (
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PAYMENT', 'REFUND')),
    started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    retry_after TIMESTAMPTZ,
    manual_review_at TIMESTAMPTZ,
    last_error TEXT,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (order_id, phase)
);

CREATE INDEX idx_saga_recovery_manual_review ON saga_recovery_state (manual_review_at, order_id)
    WHERE manual_review_at IS NOT NULL AND completed_at IS NULL;

INSERT INTO saga_recovery_state(order_id, phase, started_at)
SELECT id, 'PAYMENT', created_at FROM orders WHERE status='PAYMENT_PENDING';

INSERT INTO saga_recovery_state(order_id, phase, started_at)
SELECT o.id, 'REFUND', COALESCE(
    (SELECT MIN(h.changed_at) FROM order_status_history h
     WHERE h.order_id=o.id AND h.status='PAYMENT_REFUNDING'), o.updated_at)
FROM orders o WHERE o.status IN ('REFUND_FAILED', 'PAYMENT_REFUNDING');
