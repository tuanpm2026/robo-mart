ALTER TABLE orders ADD COLUMN reservation_id VARCHAR(100);
ALTER TABLE orders ADD COLUMN payment_id VARCHAR(100);
ALTER TABLE orders ADD COLUMN cancellation_reason TEXT;
