# Backend remediation: Waves 6–9

Reviewed on 2026-10-26 against source, Maven/npm configuration and PR #44. The continuation request references the original plan in a temporary Claude scratchpad; this document preserves its scope and execution status inside the repository.

## Plan review

The source requires JDK 25 and uses Spring Boot 4.1.2 / Spring Cloud 2025.1.4. Older Java 21 / Boot 4.0 instructions do not describe the current build. Use `backend/mvnw` with JDK 25. Run integration-test modules sequentially on the local Docker engine; keep coverage gates enabled.

The wave ordering is useful, with these implementation constraints:

- Unknown payment outcomes must retain the reservation and remain recoverable. Reservation TTL expiry must consult an authoritative order/payment outcome or use a durable hold protocol. A manual-review flag alone does not make stock release safe. Define this before enabling inventory expiry.
- `SKIP LOCKED` protects selected rows, but another poller can select later rows for the same aggregate. Aggregate ordering needs an additional claim/eligibility rule. Chain sends within an aggregate, stop that chain on failure, and parallelize independent aggregates with one bounded batch wait. Stable event IDs permit deduplication; they do not provide exactly-once delivery.
- Elasticsearch external versions prevent stale writes while the version tombstone exists. Physical deletion alone does not prevent resurrection after tombstone retention expires. Use durable logical deletion or an authoritative version/deletion check; include delayed updates in tests. Elastic documents the limited retention of deleted versions in the [v9 Delete API](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-delete).
- Splitting payment intent, provider call and result into separate transactions requires provider idempotency and recovery after a crash between charge and result persistence. Preserve the request-binding check, ledger and unique constraints. Do not classify transport uncertainty as a decline.
- Removing Redis locks from reserve also requires concurrency coverage for reserve/release/commit races, duplicate product lines, lock ordering and transactional rollback. Do not add an expiry consumer independently of payment holds.
- AOT training needs an explicit profile that disables side effects (migrations, seeding, schedulers, consumers, outbound writes). Context exit alone is insufficient.
- Install frontend dependencies with `npm ci` before formatting: the existing local dependencies had Prettier 3.8.1 while the lockfile specifies 3.9.1.

## Wave 6: merged

PR [#44](https://github.com/tuanpm2026/robo-mart/pull/44), squash commit `f813894123c9159c27e76b0936ddf53f2622d6c3`.

The merged wave includes server-side pricing, checkout idempotency, saga compare-and-set transitions, stored inventory reservation lines, gateway identity/management hardening, cart namespaces, notification authorization and email claims. Follow-up fixes use Web Crypto for guest UUID fallback and remove client-controlled cart identifiers from operation logs. No CodeQL alerts remained open for the PR at merge; no alerts were dismissed.

Migrations: order V8, inventory V4, notification V5. Infrastructure: product-service access and URL configuration for order/cart, gateway management port 8090 and monitoring/probe changes. Review the PR rollout notes, especially existing cart-key expiry and the inventory reservation-line backfill.

Executed validation:

- JDK 25: `./mvnw verify -DskipE2ETests` — BUILD SUCCESS; Maven summaries report 706 unit tests and 186 integration tests, zero failures/errors/skips, configured coverage gates passed.
- JDK 25: `./mvnw checkstyle:check` — BUILD SUCCESS (existing warnings remain).
- In `frontend/`: `npm ci`; `npm run lint --workspace=customer-website`; `npm run type-check --workspace=customer-website`; `npm run test:unit --workspace=customer-website -- --run` (237 passed); `npm run build --workspace=customer-website`; `npx prettier --check customer-website/src/` — passed.
- `docker compose -f infra/docker/docker-compose.yml --profile core --profile app config --quiet` — passed.
- `docker compose -f infra/docker/docker-compose.e2e.yml config --quiet` — passed.
- `kubectl kustomize` for `infra/k8s/base`, `infra/k8s/overlays/production`, `infra/k8s/overlays/ingress-tls` — passed.
- `gh pr checks 44 --watch` — every check passed on head `decf222978109bf991a98cf9e67e0161cc7bed6a` before merge.
- Full-stack E2E/chaos was not run in this continuation.

Aborted worktrees were removed after preserving tracked diffs, untracked files and a manifest under `/private/tmp/robo-mart-aborted-worktrees-20261026`. Three worktrees contained uncommitted Wave 7 drafts; they were not assumed complete or copied into production wholesale. The backup is local and temporary, and is not a substitute for committed implementation.

## Wave 7: in progress on `wave7/correctness`

“Implemented” below means present on this branch, not merged. Pending items retain the original scope; none is implicitly waived.

| Area | Finding | Status |
| --- | --- | --- |
| Order 1 | RPC deadlines, caller-thread steps, immutable results, uncertain payment hold | Implemented; validation recorded below |
| Order 2 | Business status mapping, bounded transient retries, fallback outside retry, refund status handling | Implemented; known-payment NOT_FOUND remains an unresolved refund, ALREADY_EXISTS is idempotent success |
| Order 3 | Propagate failed release and retain recoverable state until release acknowledgement | Implemented; applies to checkout failure, pending/confirmed cancellation and dead-saga recovery |
| Order 4 | Max age/attempts and manual-review escalation for pending payment/refund | Implemented: persisted per-phase budgets, ADMIN review/resume APIs, ERROR/metric; stock stays held |
| Order 5 | Ordered asynchronous outbox, unknown-type metric and retention | Implemented: durable claims on aggregate heads, bounded async sends, poison retention, ShedLock cleanup |
| Order 6 | Executor context propagation | Implemented: outbox executor uses Micrometer context snapshots plus correlation/trace MDC accessors; no saga step executor |
| Inventory 1 | Atomic SQL reserve without Redis; bounded contention retry | Implemented: conditional SQL in sorted product order, full transaction rollback, up to three contention attempts; dead Redis code/dependency removed |
| Inventory 2 | Commit/release consumer, deduplication/DLT, committed returns, expiry | Pending; payment uncertainty must block unsafe expiry |
| Inventory 3 | Stable outbox IDs, complete event mapping, async ordering and retention | Pending |
| Inventory 4 | Low-stock threshold crossing and transaction boundaries | Implemented: crossing-only alert; alert write failure rolls back the reservation |
| Inventory 5 | Quantity constraints, restock/list bounds | Implemented: nonnegative DB guards, restock maximum 1,000,000 and list maximum 100, checked integer arithmetic |
| Cart 1 | Atomic Redis hash operations, TTL refresh and atomic merge | Pending |
| Cart 2 | Product reverse index, per-field price updates and DLT | Pending |
| Cart 3 | Distributed expiry-warning lock, warned marker, stable ID and expiry ZSET | Pending |
| Payment 1 | Resolve dead outbox writes/publisher | Pending; make and document the ownership decision |
| Payment 2 | Intent/provider/result boundaries with idempotency preserved | Pending; requires crash-window tests |
| Payment 3 | Partial-refund accounting, currency and INVALID_ARGUMENT | Pending |
| Payment 4 | Page unbounded query | Pending |
| Notification 1 | Authenticated admin clients, least-privilege GET roles, bounded pages, error results | Pending |
| Notification 2 | Full DLQ record storage, byte replay, source-offset dedup and exponential backoff | Pending |
| Notification 3 | Reconciliation lock, processed-event retention, HTTP timeouts and bounded name cache | Pending |
| Product 1 | Versioned explicit index/mapping/alias, Vietnamese analyzer and scaled price | Pending |
| Product 2 | External versions including durable deletion semantics | Pending |
| Product 3 | Shared complete event payload including rating | Pending |
| Product 4 | Bounded paging and connectivity-only fallback | Pending |
| Product 5 | DLT and admin rebuild | Pending |
| Product 6 | Outbox ordering, async send, unknown types and retention | Pending |
| Product 7 | Image route/auth/storage, post-commit delete and cache eviction | Pending |
| Product 8 | Optional update version, conflict mapping and real trace ID | Pending |
| Product 9 | Redis SCAN clearing and synchronized cache loading | Pending |
| Product 10 | Missing admin category/audit routes | Pending |

### Wave 7 validation

Executed on JDK 25 against implementation commit `423b37b52324c801a30ed069037146324ed6d665`:

- `./mvnw verify -pl :order-service -DskipE2ETests` — BUILD SUCCESS; 173 unit tests and 39 integration tests, configured coverage gate passed.
- `./mvnw verify -DskipE2ETests` — BUILD SUCCESS across the reactor, 721 unit tests and 188 integration tests (909 total), zero failures/errors/skips in the executed suites. All configured coverage gates passed. The final run completed at 15:11 on 2026-10-26 (Asia/Ho_Chi_Minh).
- `./mvnw checkstyle:check` — BUILD SUCCESS; existing repository warnings remain.
- `npm run lint --workspace=customer-website` — passed.
- `npm run test:unit --workspace=customer-website -- --run` — 240 passed.
- `npm run build --workspace=customer-website` — passed, including type checking.
- `npx prettier --check customer-website/src/` and `git diff --check` — passed.
- Full-stack E2E/chaos was not run.

Regression coverage includes real gRPC transport cancellation for reserve/charge/refund, all five business status codes, bounded transient release retries, caller-thread result application, unacknowledged refund/release responses, all compensation entry points, PostgreSQL recovery after a failed stock release, HTTP 202 for ongoing compensation and storefront cancellation messages.

Earlier validation attempts failed on obsolete retry/cancellation expectations, Mockito restubbing of a throwing method, and malformed test imports introduced during import cleanup. Those issues were fixed before the successful final reactor run; the failed attempts are not counted as passes.

No new schema, REST response fields, proto/Avro contract or infrastructure changes are included in the current order-service subset. Checkout awaiting stock release returns HTTP 202 with its existing `INVENTORY_RELEASING` status; the storefront displays cancellation progress instead of confirmation or a delivery estimate. The deadline remains a per-attempt bound; a retried operation can take up to three deadlines plus the configured exponential backoff. Do not start Wave 8 or merge Wave 7 while the remaining correctness scope is incomplete.

## Wave 8: pending

| Finding | Status / dependency |
| --- | --- |
| 1. Modern Checkstyle engine bound to validate | Pending; fix actual violations before enabling the gate |
| 2. OSIV off, virtual threads, explicit pools and gRPC executor | Pending; verify detached access and executor behavior |
| 3. ProblemDetail and Spring 4xx handling | Pending; inspect frontend field usage before changing errors |
| 4. Boot-managed Kafka factories, observations and DLT | Pending |
| 5. Structured logging, baggage, remove duplicate tracing, OTLP setting, tracing test alignment | Pending |
| 6. Dockerfile alignment/layers/AOT/JVM flags/image digests | Pending; side-effect-free training profile required |
| 7. Spring resilience primitives, gateway breaker, JSpecify/NullAway | Pending |
| 8. JWT issuer/audience and realm mapper alignment | Pending; explicitly handle internal/external issuer hostnames |
| 9. Service-specific integration-test infrastructure | Pending |
| 10. Remove active dev profile, Locale.ROOT, security coverage exclusions, Keycloak CORS origin and proxy spoofing | Pending |

## Wave 9: pending

| Finding | Status / dependency |
| --- | --- |
| 1. Shared outbox library with ordering, claims, IDs, retention and metrics; document CDC | Pending; extract proven behavior from Wave 7 |
| 2. Confluent 8/KRaft and compatible consumer protocol | Pending; verify broker/client/schema compatibility |
| 3. S3-compatible image storage and MinIO/AWS SDK v2 | Pending; provide migration/rollout path for existing images |
| 4. Split common-core/common-web-starter, scoped errors and remove cart DB exclusions | Pending; preserve service security and configuration boundaries |

### Order recovery/outbox continuation

`./mvnw clean verify -pl :order-service -DskipE2ETests` passed on 2026-10-26 after adding the recovery and outbox changes. The outbox poller is now included in the JaCoCo gate. A prior incremental run had mismatched JaCoCo class data; the successful clean run has no such mismatch.

New migrations: order V9 (`saga_recovery_state`, including backfill for existing held payments/refunds) and V10 (outbox leases and indexes). Recovery intents commit before RPCs. Pending payment row claims use `FOR NO KEY UPDATE` so the separate intent transaction can acquire its foreign-key key-share lock without blocking its own caller. Refund acknowledgements survive a crash before the order transition and allow recovery without another provider RPC.

Operational controls:

- `saga.payment-recovery.max-age=1h`, `max-attempts=120`; `saga.refund-recovery.max-age=1h`, `max-attempts=12`. Max age must be positive and below 24 hours. Escalation keeps the order non-terminal and does not release inventory.
- ADMIN-only `GET /api/v1/admin/orders/recovery/manual-reviews?page=0&size=25` (maximum 100) lists held phases. `POST /api/v1/admin/orders/recovery/{orderId}/{PAYMENT|REFUND}/resume` grants a new budget; it does not change payment outcomes, cancel the order or release stock. Reconciliation retains the original idempotency key.
- `outbox.publish-timeout=10s`, `outbox.claim-duration=2m`, `outbox.retention=7d`. Only one pending head per aggregate is eligible, even across replicas. Unknown/corrupt heads remain unpublished and block their own aggregate. Independent aggregates still advance. An uncertain send retains its lease; replay uses the stable existing event ID.
- The new claim protocol requires old pollers to be drained before relying on its ordering guarantees. Old application versions ignore claim columns. For a strict cutover, stop/drain old order replicas before starting the new poller; the migrations alone do not make mixed-version pollers safe.
- Retention deletes only published rows in batches of 10,000 under ShedLock. No unpublished rows are deleted. Producer/Schema Registry blocking and delivery timeouts are bounded.

The overall Wave 7 scope remains incomplete; the remaining services are still pending in the table above. The successful whole-reactor run recorded earlier predates this continuation; new full-reactor validation will be recorded at the wave boundary.

### Inventory atomic-reserve continuation

Inventory no longer uses Redis for stock locking. The order reservation, stored lines, sorted conditional stock updates, movements and outbox writes share one PostgreSQL transaction. A failed line or alert write rolls back all earlier lines. Database contention is retried at most three times with bounded backoff. Releases continue to use stored reservation lines and retain release tombstones.

Migration inventory V5 adds nonnegative available/reserved constraints. It intentionally does not repair historical data: deployment fails if existing stock is negative and requires reconciliation first. No total-quantity equality constraint is introduced before the commit/return lifecycle is implemented and verified.

Dead per-item reserve/release methods, Redis lock configuration/service/exception and their implementation-specific tests were removed. Existing persistence/concurrency tests now exercise the same order-level path as gRPC. The oversell regression requires exactly ten successful reservations when only ten units exist; new regressions cover later-line rollback, alert failure rollback, threshold crossing and database constraints.

Compose core/app and E2E no longer make inventory wait for Redis. Kubernetes inventory readiness lists db/Kafka without Redis. Shared Redis configuration for other services remains unchanged. Restock DTO fields remain compatible with the storefront/admin clients; validation now caps restock quantity at 1,000,000 and product lists at 100. Overflow fails validation before mutating the item.

Final inventory `./mvnw clean verify -pl :inventory-service -DskipE2ETests` passed (74 unit + 29 integration tests), including the coverage gate. Checkstyle passed. Both Compose configurations and Kustomize base/production/ingress-tls passed.

CI found that the outbox database-lock probe's 100 ms test deadline was too short on the runner. That integration test now allows 2 seconds while retaining its transaction and `FOR UPDATE NOWAIT` assertions. The separate bounded-timeout unit regression is unchanged. `./mvnw verify -pl :order-service -Dit.test=OrderOutboxDeliveryIT -DskipE2ETests` passed after the fixture change.
