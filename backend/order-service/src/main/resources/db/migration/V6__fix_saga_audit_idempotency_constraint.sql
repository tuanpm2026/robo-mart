-- Fix saga idempotency enforcement.
--
-- V3 added a table-wide UNIQUE (idempotency_key) constraint, but the orchestrator writes
-- multiple audit rows per step with the SAME idempotency_key ("{orderId}:{stepName}") — one
-- per lifecycle status (STARTED, then SUCCESS / FAILED / TIMED_OUT). The table-wide constraint
-- let only the FIRST row (STARTED) persist and silently rejected every later row for that step,
-- so SUCCESS audit entries were never written and the
-- existsByIdempotencyKeyAndStatus(key, 'SUCCESS') idempotency check could never become true.
--
-- The intended design (see the original V3 comment "only SUCCESS records matter") is to enforce
-- one SUCCESS per step. Replace the over-broad constraint with a PARTIAL UNIQUE index scoped to
-- SUCCESS rows: idempotency is still enforced at the DB level (closing the TOCTOU race), while
-- the per-status lifecycle rows are allowed to coexist.

ALTER TABLE saga_audit_log
    DROP CONSTRAINT IF EXISTS uq_saga_audit_log_idempotency_key;

-- The plain (non-unique) lookup index from V3 is superseded by the partial UNIQUE index below.
DROP INDEX IF EXISTS idx_saga_audit_log_idempotency;

CREATE UNIQUE INDEX uq_saga_audit_log_success_idempotency
    ON saga_audit_log (idempotency_key)
    WHERE status = 'SUCCESS' AND idempotency_key IS NOT NULL;
