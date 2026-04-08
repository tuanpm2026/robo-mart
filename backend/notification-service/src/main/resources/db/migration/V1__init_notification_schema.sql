-- Notification log table for tracking all sent notifications
CREATE TABLE notification_log (
    id                BIGSERIAL PRIMARY KEY,
    notification_type VARCHAR(50)  NOT NULL,
    recipient         VARCHAR(255) NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    subject           VARCHAR(500),
    content           TEXT         NOT NULL,
    order_id          VARCHAR(50),
    trace_id          VARCHAR(64),
    error_message     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_notification_type CHECK (notification_type IN ('ORDER_CONFIRMED', 'PAYMENT_SUCCESS', 'PAYMENT_FAILED')),
    CONSTRAINT chk_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT chk_status CHECK (status IN ('SENT', 'FAILED')),
    CONSTRAINT uk_notification_order_type UNIQUE (order_id, notification_type)
);

CREATE INDEX idx_notification_log_recipient ON notification_log(recipient);
CREATE INDEX idx_notification_log_order_id ON notification_log(order_id);
CREATE INDEX idx_notification_log_type ON notification_log(notification_type);
CREATE INDEX idx_notification_log_created_at ON notification_log(created_at DESC);
