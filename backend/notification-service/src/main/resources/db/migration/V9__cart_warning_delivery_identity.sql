ALTER TABLE notification_log ADD COLUMN delivery_key varchar(100);
ALTER TABLE notification_log DROP CONSTRAINT uk_notification_order_type;
CREATE UNIQUE INDEX uk_notification_order_type ON notification_log(order_id,notification_type)
    WHERE notification_type <> 'CART_EXPIRY_WARNING';
CREATE UNIQUE INDEX uk_notification_delivery_key ON notification_log(delivery_key)
    WHERE delivery_key IS NOT NULL;
