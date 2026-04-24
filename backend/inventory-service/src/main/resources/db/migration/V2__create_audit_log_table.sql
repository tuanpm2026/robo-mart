CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    actor           VARCHAR(255) NOT NULL,
    action          VARCHAR(20)  NOT NULL,
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       VARCHAR(255) NOT NULL,
    trace_id        VARCHAR(255),
    correlation_id  VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_actor       ON audit_log(actor);
CREATE INDEX idx_audit_log_entity_type ON audit_log(entity_type);
CREATE INDEX idx_audit_log_entity_id   ON audit_log(entity_id);
CREATE INDEX idx_audit_log_trace_id    ON audit_log(trace_id);
CREATE INDEX idx_audit_log_created_at  ON audit_log(created_at);
