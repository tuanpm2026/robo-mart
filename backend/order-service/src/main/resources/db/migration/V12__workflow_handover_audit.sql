CREATE TABLE order_workflow_handover_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, order_id bigint NOT NULL REFERENCES orders,
 owner_epoch bigint NOT NULL, actor varchar(200) NOT NULL, reason varchar(1000) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
