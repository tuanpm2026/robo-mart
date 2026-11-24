CREATE TABLE reconciliation_results (
    type varchar(20) PRIMARY KEY,
    result_json text NOT NULL,
    checked_at timestamptz NOT NULL
);
CREATE TABLE admin_broadcasts (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic varchar(100) NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_broadcast_age ON admin_broadcasts(created_at);
