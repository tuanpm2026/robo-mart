# Deferred Work

## Deferred from: code review of 8-4-implement-saga-phase-b-hardened-orchestration (2026-04-16)

- **Multi-instance deployment racing on dead saga recovery** — `DeadSagaDetectionJob` has no distributed claim/lock before calling `handleDeadSaga()`; in multi-pod deployments all instances process the same stuck orders concurrently. Optimistic locking provides partial protection but compensation gRPC calls run before any status update. Fix requires distributed lock (Redis SETNX / Zookeeper) or DB-level advisory lock. Deferred: architectural change beyond story 8.4 scope.

- **`orderId` always equals `sagaId` in `logSagaStep()`** — `SagaAuditLog.orderId` is always set to `order.getId().toString()` same as `sagaId`, losing the ability to distinguish multiple sagas per order if that pattern is ever needed. Deferred: pre-existing design decision; `sagaId == orderId` is intentional by current architecture.
