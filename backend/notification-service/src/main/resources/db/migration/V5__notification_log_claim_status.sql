-- Claim-before-send delivery (Wave 6): a notification row is inserted as PENDING before the email
-- is sent and moved to SENT/FAILED afterwards. The existing uk_notification_order_type unique key is
-- the claim. Only SENT rows short-circuit redelivery; FAILED rows and PENDING rows whose claim has
-- gone stale (updated_at older than notification.email.claim-timeout) are re-claimed and retried.
ALTER TABLE notification_log ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE notification_log SET updated_at = created_at;
ALTER TABLE notification_log ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE notification_log ALTER COLUMN updated_at SET DEFAULT NOW();

ALTER TABLE notification_log ADD COLUMN attempts INTEGER NOT NULL DEFAULT 1;

ALTER TABLE notification_log DROP CONSTRAINT chk_status;
ALTER TABLE notification_log ADD CONSTRAINT chk_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'));
