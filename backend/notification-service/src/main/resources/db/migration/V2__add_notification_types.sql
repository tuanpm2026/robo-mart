-- Update notification_type check constraint to include new types
ALTER TABLE notification_log DROP CONSTRAINT chk_notification_type;
ALTER TABLE notification_log ADD CONSTRAINT chk_notification_type CHECK (
    notification_type IN (
        'ORDER_CONFIRMED', 'PAYMENT_SUCCESS', 'PAYMENT_FAILED',
        'LOW_STOCK_ALERT', 'CART_EXPIRY_WARNING'
    )
);
