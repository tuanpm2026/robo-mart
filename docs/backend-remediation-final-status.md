# Backend remediation final finding status

The original continuation plan is preserved in [the wave roadmap](backend-remediation-waves-6-9.md). This checklist covers every numbered Wave7–9 finding and the Wave6 continuation checks. “Done” describes implemented and locally verified behavior; the PR records are the authoritative CI and merge history. Production rollout is separate and auto-deploy is disabled.

## Wave6 continuation

| Finding | Status | Evidence |
| --- | --- | --- |
| Finish security/money changes and merge PR44 | Done | PR44; squash f813894123c9159c27e76b0936ddf53f2622d6c3 |
| Customer client formatting/checks | Done | Prettier, lint, type-check, unit/build passed |
| Inspect/fix CodeQL summary alerts | Done | Real logging/UUID issues fixed; no open PR alerts, none dismissed |
| Remove aborted worktrees/branches while preserving work | Done | Local backup/manifest made before removal; useful changes were implemented and verified separately |

## Wave7 correctness

| Area | Finding | Status |
| --- | --- | --- |
| Order 1 | RPC deadlines, caller-thread steps, immutable results, uncertain payment hold | Done; validation recorded below |
| Order 2 | Business status mapping, bounded transient retries, fallback outside retry, refund status handling | Done; known-payment NOT_FOUND remains an unresolved refund, ALREADY_EXISTS is idempotent success |
| Order 3 | Propagate failed release and retain recoverable state until release acknowledgement | Done; applies to checkout failure, pending/confirmed cancellation and dead-saga recovery |
| Order 4 | Max age/attempts and manual-review escalation for pending payment/refund | Done: persisted per-phase budgets, ADMIN review/resume APIs, ERROR/metric; stock stays held |
| Order 5 | Ordered asynchronous outbox, unknown-type metric and retention | Done: durable claims on aggregate heads, bounded async sends, poison retention, ShedLock cleanup |
| Order 6 | Executor context propagation | Done: outbox executor uses Micrometer context snapshots plus correlation/trace MDC accessors; no saga step executor |
| Inventory 1 | Atomic SQL reserve without Redis; bounded contention retry | Done: conditional SQL in sorted product order, full transaction rollback, up to three contention attempts; dead Redis code/dependency removed |
| Inventory 2 | Commit/release consumer, deduplication/DLT, committed returns, expiry | Done: transactional dedup, Avro/raw-byte DLT, committed returns, authenticated order reconciliation; uncertain expiry holds stock for review |
| Inventory 3 | Stable outbox IDs, complete event mapping, async ordering and retention | Done: persisted UUID, aggregate-head claims, bounded async sends, unknown retention, ShedLock cleanup; dead restock writes retired with audit archive |
| Inventory 4 | Low-stock threshold crossing and transaction boundaries | Done: crossing-only alert; alert write failure rolls back the reservation |
| Inventory 5 | Quantity constraints, restock/list bounds | Done: nonnegative DB guards, restock maximum 1,000,000 and list maximum 100, checked integer arithmetic |
| Cart 1 | Atomic Redis hash operations, TTL refresh and atomic merge | Done: Lua line operations and atomic guest transfer, immutable GET body, TTL refresh and legacy-hash conversion |
| Cart 2 | Product reverse index, per-field price updates and DLT | Done: expiring product ZSET indexes, line-only monotonic price updates, latest snapshot during migration, Avro/raw-byte DLT |
| Cart 3 | Distributed expiry-warning lock, warned marker, stable ID and expiry ZSET | Done: Redis token lease, acknowledged-generation marker, stable retry ID, expiry ZSET and failure cooldown |
| Payment 1 | Resolve dead outbox writes/publisher | Done: stop unconsumed writes, preserve historical rows in retired_outbox_events; payment/provider ledgers remain authoritative |
| Payment 2 | Intent/provider/result boundaries with idempotency preserved | Done: committed request intent, bounded fenced lease, provider outside application transactions, committed receipt; real crash-window and small-pool tests passed |
| Payment 3 | Partial-refund accounting, currency and INVALID_ARGUMENT | Done: cumulative refunded_amount, per-request refund receipts/keys, currency validation and correct gRPC mapping |
| Payment 4 | Page unbounded query | Done: reconciliation page/size <=100 with hasNext; existing payment-list payload fields retained |
| Notification 1 | Authenticated admin clients, least-privilege GET roles, bounded pages, error results | Done: registered authenticated clients, least-privilege service reads, pages <=100 and explicit ERROR/HTTP 502; full service and RBAC tests passed |
| Notification 2 | Full DLQ record storage, byte replay, source-offset dedup and exponential backoff | Done: exact key/value/header capture, source-position dedup, durable replay claims, Kafka ACK before REPLAYED, exponential retry; preview-only history requires manual review |
| Notification 3 | Reconciliation lock, processed-event retention, HTTP timeouts and bounded name cache | Done: DB-time ShedLock, bounded retention preserving active failed identities, bounded HTTP/token requests and Caffeine cache; real DB/HTTP regressions passed |
| Product 1 | Versioned explicit index/mapping/alias, Vietnamese analyzer and scaled price | Done: checked products_v1 mapping/settings, products-search alias, resumable PG bootstrap/readiness, Vietnamese folding and scaled prices; real Elasticsearch tests passed |
| Product 2 | External versions including durable deletion semantics | Done: authoritative PostgreSQL snapshots, strict external source-version+1 writes, required alias and permanent logical tombstones; delayed snapshot/cross-topic/deletion regressions passed |
| Product 3 | Shared complete event payload including rating | Done: one snapshot mapper for live edits and demo indexing, preserving nullable rating; unit and real persisted-outbox regression passed |
| Product 4 | Bounded paging and connectivity-only fallback | Done: 10,000-result window, network-cause-only fallback, mapping/auth failures propagate; real HTTP deep-page rejection and unit classification tests passed |
| Product 5 | DLT and admin rebuild | Done: exponential retry and per-topic acknowledged DLT with corrupt bytes retained, bounded resumable ADMIN reindex pages; real Kafka/Elasticsearch/HTTP regressions passed |
| Product 6 | Outbox ordering, async send, unknown types and retention | Done: fenced aggregate-head claims, persisted legacy-compatible UUIDs, bounded async sends, poison retention, DB-time ShedLock cleanup; real DB/concurrency/Kafka regressions passed |
| Product 7 | Image route/auth/storage, post-commit delete and cache eviction | Done: public GET through Gateway, persistent image volumes/PVC, commit-only deletion, rollback upload cleanup and transaction-aware cache invalidation; real filesystem/DB/HTTP and volume replacement tests passed |
| Product 8 | Optional update version, conflict mapping and real trace ID | Done: optional version, response version after flush, 409 on stale/optimistic or duplicate SKU, real trace ID; real parallel HTTP edits/creates passed |
| Product 9 | Redis SCAN clearing and synchronized cache loading | Done: SCAN batches of 1,000 and sync=true on detail/search cache loading; Redis integration and concurrent Spring cache-loader regression passed |
| Product 10 | Missing admin category/audit routes | Done: category management to Product, explicit product audit alias, global audit/reconciliation to Notification; real Gateway forwarding and RBAC regression passed |

## Wave8 platform

| Finding | Status / dependency |
| --- | --- |
| 1. Modern Checkstyle engine bound to validate | Done: Checkstyle 12.3.1 runs at validate; existing advisory severity retained |
| 2. OSIV off, virtual threads, explicit pools and gRPC executor | Done: OSIV off including real HTTP fallback; servlet/gRPC virtual execution, bounded pools; full reactor and E2E passed |
| 3. ProblemDetail and Spring 4xx handling | Done: RFC 9457 with legacy error envelope; real MVC 400/403/405/415 tests passed |
| 4. Boot-managed Kafka factories, observations and DLT | Done: Boot factories/customizers, per-record baggage, raw/Avro DLT, terminal archive retry; full reactor and E2E passed |
| 5. Structured logging, baggage, remove duplicate tracing, OTLP setting, tracing test alignment | Done: Boot ECS, OTel in tests, scoped HTTP/Kafka baggage; actual propagation regression passed |
| 6. Dockerfile alignment/layers/AOT/JVM flags/image digests | Done: all9 rendered files, source/CD/prebuilt builds, pinned layers/JVM flags, isolated training, actual cache mapping in all7 E2E JVMs |
| 7. Spring resilience primitives, gateway breaker, JSpecify/NullAway | Done: Framework retries/concurrency, Gateway transport breaker, JSpecify contracts and compile-time NullAway errors |
| 8. JWT issuer/audience and realm mapper alignment | Done: issuer/audience/JWK validation; signed-token Gateway tests, retained E2E mapper reconciliation and actual service-identity RBAC checks passed |
| 9. Service-specific integration-test infrastructure | Done: resource-specific JVM-owned fixtures; no forced fork shutdown in final full reactor |
| 10. Remove active dev profile, Locale.ROOT, security coverage exclusions, Keycloak CORS origin and proxy spoofing | Done; role/CORS/forwarded-header regressions and coverage including config passed |

## Wave9 data infrastructure

CI/merge record: [PR #47](https://github.com/tuanpm2026/robo-mart/pull/47). All local18-module reactor, style, image, schema, frontend and4 isolated E2E gates passed; see the linked validation report for counts.

| Finding | Status | Evidence |
| --- | --- | --- |
| Shared outbox library: ordering, locks/fences, IDs, retention, metrics, CDC option | Done | outbox-lib; direct PostgreSQL tests and all3 owning service suites; legacy IDs/schema unchanged |
| Confluent8/KRaft and safe KIP-848 | Done | 8.2.4/client4.2.2; retained identity/topic/schema/offset snapshots, real classic→consumer offset continuity, guarded feature enablement; production defaults classic |
| S3 image storage, MinIO fixture and AWS SDK v2 | Done | Conditional object writes, public URLs, rollback/commit cleanup, cache invalidation, legacy redirect, copy/checksum/idempotence/conflict tests and plain packaged CLI; readonly old volume retained |
| common-core/common-web-starter, service error codes, no Cart DB exclusions | Done | Conditional auto-configuration without common scanning, optional security/persistence, domain wire-code tests and real Cart no-DB/no-exclusion context assertion |

## Explicit scope boundaries

| Item | Status | Reason |
| --- | --- | --- |
| Debezium/Kafka Connect implementation | Deferred by the requested plan | Future handover design is documented; adding Connect was explicitly excluded |
| Remove the common-lib compatibility artifact/old CI selector | Deferred maintenance | Services already use the split modules; facade keeps existing CI selection exercising starter tests without workflow edits. Retire artifact and selector together |
| ZooKeeper-to-KRaft/quorum migration | Not applicable | Owned Compose brokers already used KRaft; no external production broker is managed here |
| Copy existing isolated E2E volume contents | Not applicable to retained fixture | Volume was verified empty; nonempty copy/conflict/source-preservation behavior is tested against separate real MinIO fixtures |
| Live production bucket/client/secret/feature rollout | Deferred operational rollout | No auto-deploy/live cluster apply; provision and verify using the committed rollout runbooks before manual deployment |

Detailed commands, test counts and limits: [Wave7](../_bmad-output/test-artifacts/wave7-correctness-validation-2026-10-27.md), [Wave8](../_bmad-output/test-artifacts/wave8-platform-validation-2026-10-27.md), [Wave9](../_bmad-output/test-artifacts/wave9-data-infra-validation-2026-10-27.md).
