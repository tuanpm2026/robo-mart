-- Provider-side idempotency is independent of the payment/outbox transaction.
-- Never expire settled operations: a retry after a crash must replay the same result.
CREATE TABLE mock_gateway_operations (
    operation_key VARCHAR(220) PRIMARY KEY,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    request_identity TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    transaction_id VARCHAR(100),
    failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE payments ADD COLUMN user_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN refund_transaction_id VARCHAR(100);
