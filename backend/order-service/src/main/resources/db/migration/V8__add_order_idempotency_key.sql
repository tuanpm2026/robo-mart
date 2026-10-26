-- Client-supplied checkout idempotency key. A retried create returns the original order instead of
-- creating (and charging) a second one. Keys are scoped per customer; legacy rows keep NULL, which the
-- partial index ignores.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX uq_orders_user_idempotency_key
    ON orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
