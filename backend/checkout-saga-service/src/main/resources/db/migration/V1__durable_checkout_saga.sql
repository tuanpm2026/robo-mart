CREATE TABLE saga_instances (
 saga_id uuid PRIMARY KEY, order_id varchar(100) NOT NULL UNIQUE, owner_epoch bigint NOT NULL,
 workflow_version integer NOT NULL CHECK (workflow_version=1), snapshot_json text NOT NULL,
 state varchar(40) NOT NULL, cancel_requested boolean NOT NULL DEFAULT false, reason varchar(1000) NOT NULL DEFAULT '',
 reservation_id varchar(100) NOT NULL DEFAULT '', payment_id varchar(100) NOT NULL DEFAULT '',
 charge_resolution varchar(40) NOT NULL DEFAULT '', request_sequence bigint NOT NULL,
 due_at timestamptz, phase_started_at timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0,
 claim_token uuid, claim_until timestamptz, last_error varchar(1000) NOT NULL DEFAULT '', manual_review_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX saga_due ON saga_instances(due_at) WHERE manual_review_at IS NULL;
CREATE TABLE saga_inbox (
 event_id varchar(100) PRIMARY KEY, request_id varchar(200) NOT NULL UNIQUE,
 payload_hash char(64) NOT NULL, saga_id uuid NOT NULL REFERENCES saga_instances,
 received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE saga_steps (
 saga_id uuid NOT NULL REFERENCES saga_instances, operation_key varchar(200) NOT NULL,
 payload_hash char(64) NOT NULL, dispatched_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(saga_id,operation_key)
);
CREATE TABLE saga_operator_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, saga_id uuid NOT NULL REFERENCES saga_instances,
 actor varchar(200) NOT NULL, reason varchar(1000) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
