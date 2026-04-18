# Deferred Work

## Deferred from: code review of 8-4-implement-saga-phase-b-hardened-orchestration (2026-04-16)

- **Multi-instance deployment racing on dead saga recovery** — `DeadSagaDetectionJob` has no distributed claim/lock before calling `handleDeadSaga()`; in multi-pod deployments all instances process the same stuck orders concurrently. Optimistic locking provides partial protection but compensation gRPC calls run before any status update. Fix requires distributed lock (Redis SETNX / Zookeeper) or DB-level advisory lock. Deferred: architectural change beyond story 8.4 scope.

- **`orderId` always equals `sagaId` in `logSagaStep()`** — `SagaAuditLog.orderId` is always set to `order.getId().toString()` same as `sagaId`, losing the ability to distinguish multiple sagas per order if that pattern is ever needed. Deferred: pre-existing design decision; `sagaId == orderId` is intentional by current architecture.

## Deferred from: code review of 9-2-implement-health-checks-centralized-configuration (2026-04-18)

- **W1**: Dev profiles strip `prometheus` from actuator exposure — pre-existing, mitigated in K8s by ConfigMap env var `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`. Affects local dev Prometheus scraping only.
- **W2**: Memory limits 512Mi potentially tight for heavy-dependency services (product, order) — revisit with actual load data in Story 10.3.
- **W3**: HPA CPU-only scaling — JVM services can OOM before CPU spikes. Enhancement: add Prometheus Adapter + memory-based HPA metric.
- **W4**: No TLS on api-gateway LoadBalancer — needs Ingress with cert-manager or cloud LB TLS. Story 10.4 scope.
- **W5**: No `imagePullSecrets` for ghcr.io — pods fail `ErrImagePull` if packages are private without cluster-level pull secret configuration.
- **W6**: Readiness probe `timeoutSeconds: 3` tight for DB health checks under restart load — validate under load test.
- **W7**: RateLimitingFilter fail-open on Redis error — all requests bypass rate limiting during Redis outage. Pre-existing Story 8.3 pattern.
