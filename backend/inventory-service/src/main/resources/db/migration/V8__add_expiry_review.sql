-- Expiry alone cannot prove that a timed-out payment failed. Keep uncertain stock held for review.
ALTER TABLE reservations ADD COLUMN expiry_review_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE reservations ADD COLUMN expiry_next_check_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_reservations_expiry_due ON reservations
    ((COALESCE(expiry_next_check_at, expires_at))) WHERE status = 'RESERVED';
