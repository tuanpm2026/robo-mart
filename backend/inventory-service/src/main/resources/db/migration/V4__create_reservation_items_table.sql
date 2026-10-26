-- Per-order reservation lines. Release must restore exactly what this order reserved, never what
-- the release request claims: request items are untrusted (a release issued after a failed or
-- UNAVAILABLE reserve would otherwise subtract other orders' reserved units and inflate stock).
-- Lines are written in the same transaction as the reservation row and its stock decrements.
CREATE TABLE reservation_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   VARCHAR(100) NOT NULL REFERENCES reservations (order_id),
    product_id BIGINT       NOT NULL,
    quantity   INTEGER      NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_reservation_items_order_product UNIQUE (order_id, product_id)
);

-- Backfill lines for reservations that are still RESERVED, from the per-order stock movement
-- audit trail (net of any RELEASE rows written by a rolled-back concurrent duplicate reserve).
-- Without this, pre-existing reservations would release nothing and leak reserved stock.
INSERT INTO reservation_items (order_id, product_id, quantity)
SELECT r.order_id, i.product_id, net.quantity
FROM reservations r
JOIN (
    SELECT m.order_id,
           m.inventory_item_id,
           SUM(CASE m.type WHEN 'RESERVE' THEN m.quantity WHEN 'RELEASE' THEN -m.quantity ELSE 0 END) AS quantity
    FROM stock_movements m
    GROUP BY m.order_id, m.inventory_item_id
) net ON net.order_id = r.order_id
JOIN inventory_items i ON i.id = net.inventory_item_id
WHERE r.status = 'RESERVED'
  AND net.quantity > 0;
