# Deferred Work

## Deferred from: code review of 10-1-implement-test-support-module-unit-test-foundation (2026-04-19)

- **W1 — Test naming convention violations in pre-existing tests** — 80+ methods across ImageStorageServiceTest, AdminProductServiceTest, JwtStompInterceptorTest, OrderRestControllerTest, etc. don't follow `should{Expected}When{Condition}()`. Pre-existing; address as part of a dedicated test hygiene pass in Story 10.3 or later.
- **W2 — JUnit assertDoesNotThrow/assertThrows in pre-existing files** — AdminPushServiceTest, JwtStompInterceptorTest, DeadSagaDetectionJobTest use JUnit assertions instead of AssertJ. Pre-existing; fix with test hygiene pass.
- **W3 — `new Order()` + setters in 14 pre-existing order-service unit test files** — OrderServiceCreateTest, OrderSagaOrchestratorTest, etc. construct Order entities directly. Pre-existing; TestData.order().build() pattern would require cross-module dependency design — defer to architecture decision.
- **W4 — OrderGrpcServiceTest missing assertions on orderId/status in CreateOrder success** — Improvement to add, not a correctness bug.
- **W5 — NotificationServiceExtendedTest cart expiry warning tests don't verify emailService.sendEmail()** — Improvement to add in Story 10.2 extended tests.

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
