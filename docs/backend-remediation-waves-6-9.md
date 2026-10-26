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
| Order 4 | Max age/attempts and manual-review escalation for pending payment/refund | Pending; coordinate with reservation hold/expiry protocol |
| Order 5 | Ordered asynchronous outbox, unknown-type metric and retention | Pending; include cross-replica aggregate eligibility |
| Order 6 | Executor context propagation | No saga step executor remains; audit any other executors when implementing outbox |
| Inventory 1 | Atomic SQL reserve without Redis; bounded contention retry | Pending |
| Inventory 2 | Commit/release consumer, deduplication/DLT, committed returns, expiry | Pending; payment uncertainty must block unsafe expiry |
| Inventory 3 | Stable outbox IDs, complete event mapping, async ordering and retention | Pending |
| Inventory 4 | Low-stock threshold crossing and transaction boundaries | Pending |
| Inventory 5 | Quantity constraints, restock/list bounds | Pending |
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

Validation is still in progress. Targeted tests initially exposed obsolete expectations for one RPC attempt and cancellation after a failed release; those expectations have been corrected to assert bounded retries and recoverable states. Do not treat earlier failed attempts as successful verification.

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
