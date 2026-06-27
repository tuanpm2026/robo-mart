-- Per-order reservation records make the gRPC reserve/release calls idempotent.
-- The order_id unique constraint is the idempotency key: a reserve retry/replay returns the
-- original reservation_id instead of decrementing stock again, and release short-circuits once
-- the row is RELEASED. Without this, a ReserveInventory saga-step timeout (where the server
-- reserved stock but the response was lost) leaked stock forever.
CREATE TABLE reservations (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id VARCHAR(36)  NOT NULL UNIQUE,
    order_id       VARCHAR(100) NOT NULL UNIQUE,
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
