# Deferred Work

## ✅ Resolved 2026-06-20 — TEST-IT-COVERAGE + 3 production defects

CI was silently skipping ALL integration tests — the `Integration Tests` step used `-DskipTests`, which (Maven semantics) also skips failsafe — and skipping the JaCoCo gate via `-Djacoco.skip=true`. Re-enabling failsafe locally exposed **4 modules with broken ITs** and, hidden behind them, **3 real production defects**:

- **events** — `AvroSchemaCompatibilityIT` Testcontainers networking: Kafka + Schema Registry were not on a shared Docker `Network`, registry pointed at the host-side bootstrap address. Fixed via shared `Network` + in-network listener (`ConfluentKafkaContainer.withListener`).
- **order-service + notification-service** — Spring Boot 4 `HealthEndpointGroupMembershipValidator` aborted the test ApplicationContext (readiness group includes `kafka`, but no `kafka` contributor exists in the test context). Fixed by `management.endpoint.health.validate-group-membership: false` in the `test` profile (the shared `@IntegrationTest` meta-annotation already does this).
- **PROD-1 (order-service)** — `Resilience4jConfig.addConfiguration()` registered config *templates* that were never applied to the YAML-materialised instances, so business gRPC errors (`FAILED_PRECONDITION`) were retried 3× and tripped the circuit breaker → wrong saga outcomes (e.g. "Inventory temporarily unavailable" instead of "Insufficient stock"; PAYMENT_PENDING instead of CANCELLED). Fixed: deleted the dead config; map `FAILED_PRECONDITION` → dedicated `InventoryBusinessException`/`PaymentBusinessException` listed in YAML `ignore-exceptions`; fallbacks re-throw them; compensation steps (`RefundPaymentStep`/`ReleaseInventoryStep`) now catch the new types.
- **PROD-2 (order-service)** — `V3` placed a table-wide `UNIQUE(idempotency_key)` on `saga_audit_log`, but the orchestrator writes multiple rows per step with the same key (STARTED, then SUCCESS); the SUCCESS insert silently violated the constraint and was swallowed, so the `existsByIdempotencyKeyAndStatus(key,'SUCCESS')` idempotency check could never fire. Fixed by `V6` migration: partial unique index scoped to `status='SUCCESS'`.
- **PROD-3 (product-service)** — `common-lib`'s `@Auditable` `AuditAspect` uses `SecurityContextHolder`; `common-lib` declares `spring-security-core` as `optional`; product-service had no Spring Security on its runtime classpath → admin create/update/delete threw `NoClassDefFoundError` → **HTTP 500 in production**. Fixed by adding `spring-security-core` to product-service.

CI fixed: the `Integration Tests` step is now a single `./mvnw verify -DskipE2ETests` (surefire + failsafe + JaCoCo check in one reactor). The JaCoCo threshold stays at **0.80** and is now actually enforced; all modules clear it (api-gateway raised 0.60 → 1.00 via `UserIdRelayFilterUnitTest`). Full-reactor `clean verify` is green in ~8 min.

## Triage Summary (2026-05-07)

Total open items: 19. Grouped by theme and prioritized for post-Epic-10 production hardening.

### P0 — Block real-prod traffic (do before opening to external users)

| ID | Source | Item | Why P0 | Status |
| --- | --- | --- | --- | --- |
| K8S-TLS | 9.2 W4 | No TLS on api-gateway LoadBalancer | Public ingress over plaintext | ✅ Done 2026-05-07 — new `overlays/ingress-tls/` with cert-manager ClusterIssuer + nginx Ingress + api-gateway → ClusterIP patch |
| K8S-PULL | 9.2 W5 | No `imagePullSecrets` for ghcr.io | Pods fail `ErrImagePull` if packages go private | ✅ Done 2026-05-07 — `serviceaccount.yml` patches default SA + `ghcr-pull-secret` template |
| GW-RL-FAIL | 9.2 W7 | RateLimitingFilter fail-open on Redis error | Whole-system DoS exposure during Redis outage | ✅ Done 2026-05-07 — detect `X-RateLimit-Remaining=-1` marker → 503 + Retry-After |
| SAGA-LOCK | 8.4 saga-recovery | DeadSagaDetectionJob has no distributed lock | Multi-pod deployments duplicate compensation calls (double refund risk) | ✅ Done 2026-05-07 — ShedLock + JdbcTemplateLockProvider, `@SchedulerLock` on detectAndRecoverDeadSagas |

### P1 — Address before scaling load or table growth

| ID | Source | Item | Why P1 |
| --- | --- | --- | --- |
| K8S-MEM | 9.2 W2 | 512Mi limits tight for product/order | Likely OOM under load — needs load test data |
| K8S-HPA-MEM | 9.2 W3 | HPA CPU-only — JVM can OOM before CPU spikes | Add Prometheus Adapter + memory metric |
| K8S-PROBE-TO | 9.2 W6 | Readiness `timeoutSeconds: 3` tight | Validate with restart-storm load test |
| RECON-PAGE | 9.3 W8 | `findAll()` in reconciliation summaries | Add pagination before tables grow past ~10k rows |
| CHAOS-CB | 10.3 W2 | CB state + DLQ not asserted in chaos test | Quality gap — chaos test passes without verifying behavior |

### P2 — Hygiene / future-proofing

| ID | Source | Item | Why P2 |
| --- | --- | --- | --- |
| TEST-NAME | 10.1 W1 | 80+ test methods break naming convention | Cosmetic, dedicated hygiene PR |
| TEST-ASSERTJ | 10.1 W2 | JUnit asserts in 3 test files | Cosmetic, hygiene PR |
| TEST-BUILDER | 10.1 W3 | `new Order()` + setters in 14 test files | Needs cross-module test-data design — architecture decision |
| TEST-GRPC | 10.1 W4 | OrderGrpcServiceTest missing orderId/status asserts | Improvement, low risk |
| TEST-NOTIF | 10.1 W5 | NotificationServiceExtendedTest doesn't verify sendEmail | Improvement |
| CHAOS-K8S | 10.3 W1 | ServiceKillChaosIT uses docker stop, not K8s probe restart | Revisit only if E2E moves to kind/minikube |
| CHAOS-GRPC | 10.3 W3 | Chaos Monkey at Spring layer, not gRPC transport | Needs Toxiproxy for true gRPC chaos |
| ARCH-BATCH | 10.4 W1 | ArchUnit `@BatchMapping` exclusion via string match | Monitor for false positives in CI |
| CI-SLA | 10.4 W2 | Backend CI 20-min timeout vs 14-min expected | Tune after collecting real CI timing data |
| TEST-IT-COVERAGE | CI investigation 2026-05-08 | CI Integration Tests step uses `-DskipTests` (skips both surefire AND failsafe via Maven flag semantics), so failsafe never runs and JaCoCo check fires on stale unit-only data. Workaround: added `-Djacoco.skip=true` to Step 4 — JaCoCo plugin disabled there, no check enforced in CI. Local `mvn verify` reveals broken ITs in `events` module (and likely others); api-gateway unit-only coverage is 0.59 (well below 0.80). | Fix bit-rotted ITs per module (events first), then either gộp Step 3+4 into a single `mvn verify` or run failsafe goals explicitly. Re-enable JaCoCo check once aggregate coverage actually meets 0.80 |
| SAGA-ID | 8.4 saga-id | `orderId == sagaId` always | Pre-existing design choice; revisit only if multi-saga-per-order pattern needed |
| RETRY-NOOP | 2026-06-20 review | `@Retry` never retries transient gRPC errors (UNAVAILABLE/DEADLINE_EXCEEDED) — Retry is the outer aspect, CircuitBreaker the inner one, and both share the method's `fallbackMethod` (catches Throwable), so the fallback fires on the first failure before Retry observes it. | Pre-existing; the circuit breaker still records/trips correctly on transient errors, so resilience is "fail-fast to breaker," not "retry then fail." A real fix needs aspect reorder or `retry-exceptions` change + load testing — defer. Misleading code comment corrected 2026-06-20. |

### Theme rollup

- **Production hardening (K8s + gateway)** — 7 items: TLS, imagePullSecrets, rate-limit fail-open, memory sizing, HPA memory, probe timeout, distributed saga lock
- **Test hygiene** — 5 items: naming, AssertJ, builder pattern, grpc test asserts, notification test asserts
- **Chaos/E2E test fidelity** — 3 items: K8s probe path, CB state assert, gRPC transport injection
- **CI quality gates** — 2 items: ArchUnit BatchMapping watch, CI timeout SLA tuning
- **Reconciliation scalability** — 1 item: pagination
- **Saga design** — 1 item: orderId/sagaId distinction

### Recommended sequencing

1. **Quick wins (P0 batch 1)**: K8S-PULL, GW-RL-FAIL — small focused PRs, no architectural change
2. **Production gate (P0 batch 2)**: K8S-TLS (Ingress + cert-manager), SAGA-LOCK (Redis SETNX) — bigger but unblocks real traffic
3. **Load-driven decisions (P1)**: run k6 perf suite from 10.3 → derive K8S-MEM, K8S-HPA-MEM, K8S-PROBE-TO actual values
4. **Hygiene sweep (P2)**: single PR for TEST-NAME + TEST-ASSERTJ + TEST-GRPC + TEST-NOTIF — bundle to amortize review cost
5. **Defer indefinitely**: SAGA-ID, CHAOS-K8S, CHAOS-GRPC — revisit only if triggering condition (multi-saga, kind/minikube migration, Toxiproxy investment) materializes

---

## Deferred from: code review of 10-1-implement-test-support-module-unit-test-foundation (2026-04-19)

- **W1 — Test naming convention violations in pre-existing tests** — 80+ methods across ImageStorageServiceTest, AdminProductServiceTest, JwtStompInterceptorTest, OrderRestControllerTest, etc. don't follow `should{Expected}When{Condition}()`. Pre-existing; address as part of a dedicated test hygiene pass in Story 10.3 or later.
- **W2 — JUnit assertDoesNotThrow/assertThrows in pre-existing files** — AdminPushServiceTest, JwtStompInterceptorTest, DeadSagaDetectionJobTest use JUnit assertions instead of AssertJ. Pre-existing; fix with test hygiene pass.
- **W3 — `new Order()` + setters in 14 pre-existing order-service unit test files** — OrderServiceCreateTest, OrderSagaOrchestratorTest, etc. construct Order entities directly. Pre-existing; TestData.order().build() pattern would require cross-module dependency design — defer to architecture decision.
- **W4 — OrderGrpcServiceTest missing assertions on orderId/status in CreateOrder success** — Improvement to add, not a correctness bug.
- **W5 — NotificationServiceExtendedTest cart expiry warning tests don't verify emailService.sendEmail()** — Improvement to add in Story 10.2 extended tests.

## Deferred from: code review of 10-3-implement-e2e-performance-chaos-tests (2026-04-20)

- **W1 — K8s liveness probe restart not exercised in chaos tests** — `ServiceKillChaosIT` uses `docker stop/start` instead of K8s pod restart via liveness probe. Docker Compose environment does not support K8s behavior. Revisit if project migrates to kind/minikube for E2E testing.
- **W2 — Circuit Breaker state and DLQ capture not explicitly verified in AC4** — Test only checks order status (PENDING/CANCELLED), not that CB transitioned to OPEN or that DLQ has a message. Adding CB state assertion requires querying Resilience4j actuator endpoint and Kafka admin API — architectural investment beyond current story scope.
- **W3 — Chaos Monkey injects at Spring service layer, not gRPC transport (AC5)** — `chaos.monkey.watcher.service=true` intercepts Spring `@Service` beans, not the gRPC Netty transport layer. True gRPC-path latency injection requires a gRPC interceptor or Toxiproxy. Pre-existing Chaos Monkey limitation.

## Deferred from: code review of 10-4-implement-ci-cd-pipelines-quality-gates (2026-04-20)

- **W1 — ArchUnit @BatchMapping exclusion uses string annotation matching** — `CONTROLLERS_MUST_NOT_ACCESS_REPOSITORIES` predicate checks for `"org.springframework.graphql.data.method.annotation.BatchMapping"` via string comparison in ArchUnit's API. If class loading behavior changes or annotation scanning differs, the GraphQL controller exclusion may silently fail and cause false ArchUnit violations. Documented in Dev Notes; monitor for false positives in CI runs.
- **W2 — Backend CI timeout (20 min) leaves no margin for 15-min SLA (AC5/NFR49)** — Spec's performance table shows ~14 min expected for backend CI on warm cache; with 20 min timeout, any cache miss or slow Testcontainers startup can breach AC5. Defer until actual CI timing data is available to tune timeout and identify bottlenecks.

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

## Deferred from: code review of 9-3-implement-service-discovery-reconciliation-audit-trail (2026-04-24)

- **W8**: `findAll()` full table scan trong reconciliation summary endpoints (inventory-service, payment-service, order-service) — không có pagination. Chấp nhận được ở scale hiện tại nhưng cần thêm pagination khi tables grow. Địa chỉ: InventoryService.getReconciliationSummary(), PaymentService.getReconciliationSummary(), OrderService.getOrderReconciliationSummary().
