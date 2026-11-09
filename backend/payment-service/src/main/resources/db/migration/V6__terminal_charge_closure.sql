-- Uses the same provider order advisory lock as durable settlement. Never expire these gates.
CREATE TABLE mock_gateway_charge_closures (
    order_id VARCHAR(100) PRIMARY KEY,
    operation_key VARCHAR(220) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    request_identity TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
