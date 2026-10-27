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

## Wave 7: merged

Merged as PR #45, squash commit `7f9167b099e1c7167c61267d5a1584b2d7aa1d3b`. All implementation findings below are included. Final validation: 974 reactor tests, 3 isolated E2E/chaos tests and 327 frontend tests; all 16 CI checks passed. The user confirmed on 2026-10-27 that auto-deploy is not currently running, resolving the pre-merge deployment dependency. Keep the documented live provisioning/cutover requirements for the eventual rollout.

| Area | Finding | Status |
| --- | --- | --- |
| Order 1 | RPC deadlines, caller-thread steps, immutable results, uncertain payment hold | Implemented; validation recorded below |
| Order 2 | Business status mapping, bounded transient retries, fallback outside retry, refund status handling | Implemented; known-payment NOT_FOUND remains an unresolved refund, ALREADY_EXISTS is idempotent success |
| Order 3 | Propagate failed release and retain recoverable state until release acknowledgement | Implemented; applies to checkout failure, pending/confirmed cancellation and dead-saga recovery |
| Order 4 | Max age/attempts and manual-review escalation for pending payment/refund | Implemented: persisted per-phase budgets, ADMIN review/resume APIs, ERROR/metric; stock stays held |
| Order 5 | Ordered asynchronous outbox, unknown-type metric and retention | Implemented: durable claims on aggregate heads, bounded async sends, poison retention, ShedLock cleanup |
| Order 6 | Executor context propagation | Implemented: outbox executor uses Micrometer context snapshots plus correlation/trace MDC accessors; no saga step executor |
| Inventory 1 | Atomic SQL reserve without Redis; bounded contention retry | Implemented: conditional SQL in sorted product order, full transaction rollback, up to three contention attempts; dead Redis code/dependency removed |
| Inventory 2 | Commit/release consumer, deduplication/DLT, committed returns, expiry | Implemented: transactional dedup, Avro/raw-byte DLT, committed returns, authenticated order reconciliation; uncertain expiry holds stock for review |
| Inventory 3 | Stable outbox IDs, complete event mapping, async ordering and retention | Implemented: persisted UUID, aggregate-head claims, bounded async sends, unknown retention, ShedLock cleanup; dead restock writes retired with audit archive |
| Inventory 4 | Low-stock threshold crossing and transaction boundaries | Implemented: crossing-only alert; alert write failure rolls back the reservation |
| Inventory 5 | Quantity constraints, restock/list bounds | Implemented: nonnegative DB guards, restock maximum 1,000,000 and list maximum 100, checked integer arithmetic |
| Cart 1 | Atomic Redis hash operations, TTL refresh and atomic merge | Implemented: Lua line operations and atomic guest transfer, immutable GET body, TTL refresh and legacy-hash conversion |
| Cart 2 | Product reverse index, per-field price updates and DLT | Implemented: expiring product ZSET indexes, line-only monotonic price updates, latest snapshot during migration, Avro/raw-byte DLT |
| Cart 3 | Distributed expiry-warning lock, warned marker, stable ID and expiry ZSET | Implemented: Redis token lease, acknowledged-generation marker, stable retry ID, expiry ZSET and failure cooldown |
| Payment 1 | Resolve dead outbox writes/publisher | Implemented: stop unconsumed writes, preserve historical rows in retired_outbox_events; payment/provider ledgers remain authoritative |
| Payment 2 | Intent/provider/result boundaries with idempotency preserved | Implemented: committed request intent, bounded fenced lease, provider outside application transactions, committed receipt; real crash-window and small-pool tests passed |
| Payment 3 | Partial-refund accounting, currency and INVALID_ARGUMENT | Implemented: cumulative refunded_amount, per-request refund receipts/keys, currency validation and correct gRPC mapping |
| Payment 4 | Page unbounded query | Implemented: reconciliation page/size <=100 with hasNext; existing payment-list payload fields retained |
| Notification 1 | Authenticated admin clients, least-privilege GET roles, bounded pages, error results | Implemented: registered authenticated clients, least-privilege service reads, pages <=100 and explicit ERROR/HTTP 502; full service and RBAC tests passed |
| Notification 2 | Full DLQ record storage, byte replay, source-offset dedup and exponential backoff | Implemented: exact key/value/header capture, source-position dedup, durable replay claims, Kafka ACK before REPLAYED, exponential retry; preview-only history requires manual review |
| Notification 3 | Reconciliation lock, processed-event retention, HTTP timeouts and bounded name cache | Implemented: DB-time ShedLock, bounded retention preserving active failed identities, bounded HTTP/token requests and Caffeine cache; real DB/HTTP regressions passed |
| Product 1 | Versioned explicit index/mapping/alias, Vietnamese analyzer and scaled price | Implemented: checked products_v1 mapping/settings, products-search alias, resumable PG bootstrap/readiness, Vietnamese folding and scaled prices; real Elasticsearch tests passed |
| Product 2 | External versions including durable deletion semantics | Implemented: authoritative PostgreSQL snapshots, strict external source-version+1 writes, required alias and permanent logical tombstones; delayed snapshot/cross-topic/deletion regressions passed |
| Product 3 | Shared complete event payload including rating | Implemented: one snapshot mapper for live edits and demo indexing, preserving nullable rating; unit and real persisted-outbox regression passed |
| Product 4 | Bounded paging and connectivity-only fallback | Implemented: 10,000-result window, network-cause-only fallback, mapping/auth failures propagate; real HTTP deep-page rejection and unit classification tests passed |
| Product 5 | DLT and admin rebuild | Implemented: exponential retry and per-topic acknowledged DLT with corrupt bytes retained, bounded resumable ADMIN reindex pages; real Kafka/Elasticsearch/HTTP regressions passed |
| Product 6 | Outbox ordering, async send, unknown types and retention | Implemented: fenced aggregate-head claims, persisted legacy-compatible UUIDs, bounded async sends, poison retention, DB-time ShedLock cleanup; real DB/concurrency/Kafka regressions passed |
| Product 7 | Image route/auth/storage, post-commit delete and cache eviction | Implemented: public GET through Gateway, persistent image volumes/PVC, commit-only deletion, rollback upload cleanup and transaction-aware cache invalidation; real filesystem/DB/HTTP and volume replacement tests passed |
| Product 8 | Optional update version, conflict mapping and real trace ID | Implemented: optional version, response version after flush, 409 on stale/optimistic or duplicate SKU, real trace ID; real parallel HTTP edits/creates passed |
| Product 9 | Redis SCAN clearing and synchronized cache loading | Implemented: SCAN batches of 1,000 and sync=true on detail/search cache loading; Redis integration and concurrent Spring cache-loader regression passed |
| Product 10 | Missing admin category/audit routes | Implemented: category management to Product, explicit product audit alias, global audit/reconciliation to Notification; real Gateway forwarding and RBAC regression passed |

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

## Wave 8: merged

PR [#46](https://github.com/tuanpm2026/robo-mart/pull/46), squash commit `c6d72677b7e8cdc1c23e83942c97d1f2d2030e9f`. All15 CI checks passed on head a1c5e71291a04eb044f9e0cf05eb031c377a2e33; no open CodeQL alert on the PR merge ref, none dismissed. Final local gates:985 backend tests +3 real E2E/chaos +327 frontend tests, coverage/style/schema/configuration passed. No new Flyway migration; production audience provisioning and manual rollout remain operational prerequisites.

| Finding | Status / dependency |
| --- | --- |
| 1. Modern Checkstyle engine bound to validate | Implemented: Checkstyle 12.3.1 runs at validate; existing advisory severity retained |
| 2. OSIV off, virtual threads, explicit pools and gRPC executor | Implemented and verified: OSIV off including real HTTP fallback; servlet/gRPC virtual execution, bounded pools; full reactor and E2E passed |
| 3. ProblemDetail and Spring 4xx handling | Implemented RFC 9457 with legacy error envelope; real MVC 400/403/405/415 tests passed |
| 4. Boot-managed Kafka factories, observations and DLT | Implemented and verified: Boot factories/customizers, per-record baggage, raw/Avro DLT, terminal archive retry; full reactor and E2E passed |
| 5. Structured logging, baggage, remove duplicate tracing, OTLP setting, tracing test alignment | Implemented: Boot ECS, OTel in tests, scoped HTTP/Kafka baggage; actual propagation regression passed |
| 6. Dockerfile alignment/layers/AOT/JVM flags/image digests | Implemented and verified: all9 rendered files, source/CD/prebuilt builds, pinned layers/JVM flags, isolated training, actual cache mapping in all7 E2E JVMs |
| 7. Spring resilience primitives, gateway breaker, JSpecify/NullAway | Implemented: Framework retries/concurrency, Gateway transport breaker, JSpecify contracts and compile-time NullAway errors |
| 8. JWT issuer/audience and realm mapper alignment | Implemented issuer/audience/JWK validation; signed-token Gateway tests, retained E2E mapper reconciliation and actual service-identity RBAC checks passed |
| 9. Service-specific integration-test infrastructure | Implemented and verified: resource-specific JVM-owned fixtures; no forced fork shutdown in final full reactor |
| 10. Remove active dev profile, Locale.ROOT, security coverage exclusions, Keycloak CORS origin and proxy spoofing | Implemented; role/CORS/forwarded-header regressions and coverage including config passed |

## Wave 9: in progress on `wave9/data-infra`

| Finding | Status / dependency |
| --- | --- |
| 1. Shared outbox library with ordering, claims, IDs, retention and metrics; document CDC | Implemented shared outbox engine/JDBC claims/settings/retention/identity; focused library and service checks passed; final wave gates remain |
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


### Inventory lifecycle, expiry and outbox continuation

Inventory V6 introduces reservation expiry timestamps (default 24 hours), processed-event deduplication and scheduler locks. CONFIRMED events atomically claim RESERVED → COMMITTED and consume reserved/total units from stored lines. CANCELLED returns RESERVED units or returns COMMITTED units to available/total; duplicate or late confirmation cannot resurrect a released reservation. Event deduplication commits with stock changes. Processing errors retry exponentially before DLT; malformed Avro retains original bytes.

Inventory V7 persists an event UUID when inserting the outbox row and adds expiring delivery claims. It archives historical `stock_restocked` rows, with their original payload and retirement reason, in `retired_outbox_events`; new restocks retain the authoritative RESTOCK stock movement without emitting an event that has no Avro contract or consumer. Reserved/released/threshold alerts retain their existing contracts. Unknown types remain pending and block their aggregate; no event is silently acknowledged. Published-row retention defaults to seven days.

Inventory V8 adds the durable expiry-review flag and next-check timestamp. A ShedLock job reconciles at most ten expired reservations per pass, outside database transactions. It authenticates via client credentials and reads only `GET /api/v1/internal/inventory/orders/{id}/status`. CANCELLED releases stock; CONFIRMED/SHIPPED/DELIVERED commits it. Every unknown, nonterminal or inaccessible order keeps stock held, gets an ERROR/metric once and is checked again after five minutes. The original TTL remains unchanged. The deliberate safety adjustment to the original plan is that elapsed time alone cannot release uncertain charges. Operators can inspect `expiry_review_at` and reconcile the Order recovery queue; never force-release an uncertain payment.

The new INVENTORY_SERVICE role can only read that minimal internal endpoint; customer/admin order data and customer writes remain forbidden. The endpoint has no gateway route. Both order HTTP connect/read and token connect/read timeouts are bounded (1/2 seconds). Tokens are cached and no unauthenticated fallback exists. Compose receives the same `INVENTORY_SERVICE_CLIENT_SECRET` in Keycloak and Inventory; production imports use environment substitution, and E2E uses an isolated fixture credential. K8s adds `inventory-service-auth` and allows inventory → order:8083 only. Provision the role, confidential client and matching generated secret in existing realms before rollout: Keycloak startup import does not update existing realms. Apply the Secret before rolling Inventory. Missing/invalid credentials fail closed and cause expiry holds, not stock release.

Executed validation on JDK 25:

- `./mvnw install -pl :test-support -am -DskipTests -DskipITs -DskipE2ETests` — success; dependency installation only.
- `./mvnw clean verify -pl :inventory-service -DskipE2ETests` — success, 75 unit + 47 integration tests, coverage passed. Includes commit/cancel races, transactional dedup rollback, real Kafka retry/DLT, original corrupt-byte retention, aggregate ordering/leases, stable IDs, archive migration, expiry holds and authenticated client token caching/failure behavior.
- `./mvnw verify -pl :order-service -Dit.test=OrderAdminRestIT -DskipE2ETests` — success, 180 unit + 10 selected integration tests, coverage passed. Tests the restricted service role and rejects customer/admin access to the internal endpoint.
- Both Compose configuration checks and Kustomize base/production/ingress-tls renders — passed.

Earlier TTL integration assertions were blocked by the scheduler's minimum lock after its initial empty run; the logic tests now call the unwrapped job, while production retains ShedLock. A subsequent unit-test compilation failure from treating MeterRegistry as AutoCloseable was corrected. The successful inventory run still reports a Surefire forced fork shutdown after the cached Spring contexts take more than 30 seconds to close; all 122 executed tests and coverage pass. This shutdown warning remains to be investigated before the final Wave 7 reactor run. Full-stack E2E and live Keycloak provisioning have not been executed for this subset.


### Atomic carts and expiry warnings

Cart storage now uses one hash per cart, with one JSON field per product and metadata fields. Lua atomically adds (quantity cap 999), sets/removes a line, refreshes TTL and transfers guest → user. GET reads the current hash and refreshes expiry/index TTL without rewriting the cart body or updated timestamp. Reverse product indexes are expiring ZSETs, so product updates touch only affected lines, preserve concurrent quantities, ignore older price timestamps and remove stale memberships. A latest product snapshot with a bounded TTL closes the event window while legacy hashes are converted. Both Avro processing failures and corrupt original bytes have a verified DLT path.

Spring Data Redis repositories and @RedisHash are removed. Existing Spring Data hashes retain their keys and are converted atomically on first access; this one-time storage migration is the exception to ordinary GET's immutable hash body. A bounded background batch drains the old `cart` repository set, creates reverse/expiry indexes and removes stale membership. Malformed hashes remain unchanged for operator review. The actual Spring Data Redis converter is used in migration tests, preserving names, decimal prices, quantities, user identity, timestamps and remaining TTL.

Rollout: stop all old cart writers before starting the new format. The K8s cart Deployment uses Recreate for this migration; mixed old/new replicas would interpret the same hash differently and can lose data. Compose already replaces the old container. Expect a brief cart interruption during cutover. Do not roll back to a Spring Data writer against converted hashes; restore a compatible application or a separately verified Redis backup instead. No cart deletion/reset or Redis volume removal is part of this change.

Warnings use a Redis lease with token-checked release, expire-time ZSET selection, at most five sends per pass, one-minute polling and a five-minute failure cooldown. A cart is marked warned only after Kafka acknowledgement; uncertain retries retain the event ID derived from cart + expiry generation. Successful/irrelevant candidates are removed without removing a concurrently refreshed generation. Activity creates a new expiry generation. The product reverse indexes and warning markers have TTLs; legacy repository memberships are no longer created. Kafka readiness is now backed by a real bounded probe.

Executed on JDK25, with the VS Code Java language server temporarily suspended during Maven and automatically resumed afterwards (its background ECJ builder was overwriting target classes with unresolved-compilation stubs):

- `./mvnw clean verify -pl :cart-service -DskipE2ETests` — success, 45 unit + 40 integration tests, coverage passed. Redis tests cover concurrent add/GET, capped sums, parallel repeated merge + user updates, price/quantity races, stale snapshots, real legacy conversion, migration event windows and warning acknowledgement/retry deduplication. Real Kafka tests cover retry → Avro DLT and exact original corrupt-byte delivery.
- `./mvnw checkstyle:check -pl :cart-service` — success, existing naming warnings remain.
- Both Compose config checks and Kustomize base/production/ingress-tls — passed.

The first Kafka test attempt matched a nanosecond Instant against Avro's millisecond timestamp and therefore did not inject the intended failure; the fixture now uses millisecond precision. The successful run includes all suites and no forced JVM shutdown warning. TTL HTTP tests now assert configured TTL against Redis, shorten only the test key's expiry, and poll Redis existence rather than sleeping 150 seconds or refreshing TTL through HTTP polling. Test-profile-only immediate Tomcat shutdown and disabled outbound OTLP metrics avoid delayed cached-context teardown; production graceful shutdown is unchanged.


Keycloak smoke verification: an isolated ephemeral Keycloak 26.1.4 container successfully imported the committed developer realm. With the secret supplied only through the container environment, client_credentials returned a token whose only realm role was INVENTORY_SERVICE; a wrong secret returned HTTP 401. The smoke container was stopped and removed afterwards. No live realm or persistent Keycloak database was modified. This validates import/client mapping, not a complete production deployment.


### Payment intents, partial refunds and dead outbox retirement

Payment now commits an immutable request binding and PROCESSING intent before calling the provider, then commits its receipt/cache in another short transaction. Application advisory locks remain in the same order/key namespaces and are never held across provider work. Caller transactions are suspended; intent/result transactions use REQUIRES_NEW. A database-time 60-second lease and UUID completion token bound competing attempts and fence late finalizers. Duplicate requests briefly wait outside transactions and then return retryable unavailability if the original operation is still busy. Provider settlement retains its independent durable idempotency/order-binding ledger and partial unique index; a process crash after settlement replays the same provider receipt. The mock provider admits at most two operations per instance, matching its pool, and connection acquisition is bounded to one second.

Refund intents bind payment, key, currency and amount. Distinct partial refunds require distinct explicit keys; a retry must reuse the same key. The new optional proto field is additive: old clients retain the original single full-refund identity. Partial refunds without a key are rejected. Pending uncertain refunds reserve the remaining balance for their original key; competing requests cannot over-refund. Each successful receipt increments refunded_amount once; status becomes REFUNDED only when the captured amount is fully returned. Currency is validated against the payment and refund validation maps to INVALID_ARGUMENT.

Payment V5 adds processing tokens/leases, refunded_amount with a captured-amount bound, and refund_intents. It derives historical refund amounts only from durable provider receipts. REFUNDED history without a receipt keeps a NULL amount rather than inventing one; old REFUNDED rows remain protected from further refunds and need operator reconciliation before any correction. Historically partial refunds incorrectly marked REFUNDED are not silently reopened. Reconcile their provider receipts and business order before changing historical state.

The same migration renames the old outbox table to retired_outbox_events, preserving every historical row/payload. There is no publisher or consumer for payment_processed/payment_refunded, and synchronous Order orchestration already uses durable Payment/provider results. New dead writes and unused JPA outbox types are removed instead of creating an unowned event stream. Kafka/Avro dependencies, configuration and Compose startup dependencies are removed from payment; readiness now requires the database. E2E/chaos source assertions inspect finalized payment intent/provider receipts instead of dead outbox rows. Full-stack E2E/chaos has not been executed for this change.

Reconciliation uses bounded page/size (maximum 100), retaining payments/generatedAt and adding page/size/hasNext. Notification must iterate these pages before Wave 7 can merge. Rollout must deploy a compatible application with V5; old payment writers reference the retired table and must be stopped before migration. Keep a database backup and verify the historical refund reconciliation before production rollout. No provider ledger or retired outbox rows are deleted by the migration.

Executed on JDK25, with the Java language server temporarily suspended/resumed during Maven:

- `./mvnw clean install -pl :proto -am -DskipTests -DskipITs -DskipE2ETests` — success, dependency installation only.
- In backend/proto: `buf lint`; `buf breaking --against '../../.git#branch=main,subdir=backend/proto'` — passed, WIRE rules.
- `./mvnw clean verify -pl :payment-service -DskipE2ETests` — success, 42 unit + 33 integration tests, coverage passed. Includes all 18 PaymentIdempotencyIT tests, process recreation, charge/refund finalization crashes, a two-connection application pool, caller rollback independence, exact request binding, equal partial amounts with distinct keys, competing partial refunds, currency rejection through real gRPC, and historical receipt/archive migration.
- `./mvnw checkstyle:check -pl :payment-service,:proto` — success, existing warnings remain.
- Both Compose configurations and Kustomize base/production/ingress-tls — passed.

The payment integration fixtures now start PostgreSQL only because this service owns no Kafka, Redis or Elasticsearch behavior. No coverage or quality gates were disabled. The final verification completed in 27 seconds without test failures or forced JVM shutdown.


Inventory publisher follow-up: `./mvnw clean verify -pl :inventory-service -DskipE2ETests` passed with 75 unit + 48 integration tests and coverage. A real Kafka stock-reserved round trip now verifies the persisted outbox UUID and payload. Test-only background polling starts after an hour; delivery tests explicitly invoke the real poller, keeping unrelated persistence contexts isolated. Cached-context JVM teardown still exceeds Surefire's grace period; test success does not imply that warning is resolved.


### Notification DLQ capture and actual replay

Notification V6 preserves complete Kafka key/value bytes, headers (including duplicate names/null values), source topic/partition/offset/timestamp, source event identity and consumer group. Source-position uniqueness replaces aggregate-key deduplication: separate failures for the same aggregate are not discarded. Raw deserializers capture bytes before decoding, including Kafka's ByteBuffer key path. DLT recovery forwards those bytes without Avro reserialization, so corrupt envelopes and binary/null keys or values remain recoverable. Main retries use exponential backoff; the terminal DLQ monitor retries persistence indefinitely with a bounded backoff and never swallows database failures.

Individual and batch retry share one path: commit a two-minute UUID claim, publish the exact original record/partition/timestamp and business headers without a database transaction, then conditionally mark REPLAYED only after Kafka acknowledgement. Unknown acknowledgement retains the lease; known broker failure releases it for retry. A stale owner cannot acknowledge a newer claim. Concurrency is bounded to ten deliveries, batch selection to 25 IDs and acknowledgement waits to ten seconds by default. Late completion uses an executor with Micrometer context plus the captured caller trace/MDC; database work never blocks Kafka's I/O callback thread. REPLAYED means broker acknowledgement, not proof of downstream business processing.

Legacy rows only contain previews and cannot be reconstructed safely. V6 preserves them and changes their old PENDING/RESOLVED/FAILED_RETRY labels to LEGACY_REVIEW, including records that the old fake retry marked resolved without publishing. They remain visible, and automatic replay is rejected. Admin lists use a closed projection so paged reads do not load raw message bodies. Binary key display uses Base64; the original BYTEA stays unchanged. The admin UI distinguishes pending acknowledgement, broker replay and legacy review, disables unavailable/busy replay, polls active replay claims and displays the actual acknowledged batch count.

Rollout: stop old notification consumers/writers before V6 and the raw DLT format. The notification K8s Deployment uses Recreate. Existing legacy DLT records are archived for manual review rather than being guessed/reencoded. No historical failed record is deleted by this migration. Monitor pending/legacy records after rollout; do not mistake broker replay for successful notification delivery.

Executed on JDK25 with background Java compilation suspended/resumed during Maven:

- `./mvnw clean verify -pl :notification-service -DskipE2ETests` — success, 113 unit + 14 integration tests, coverage passed. Real isolated Kafka/Postgres tests verify exact Avro and malformed-byte capture, original-consumer replay, acknowledgement state, binary keys, source dedup, concurrent claims, lease fencing, null payloads, propagated DB failure/recovery and legacy migration. Existing notification delivery and alert suites also pass.
- `./mvnw checkstyle:check -pl :notification-service` — success, existing warnings remain.
- Admin: `npm run type-check --workspace=admin-dashboard`; `npm run test:unit --workspace=admin-dashboard -- --run` (86 passed); `npm run build --workspace=admin-dashboard` — passed. The changed UI/API/store/test files were formatted with the lockfile's Prettier.

Failed intermediate checks caught repository-package projection coupling, Kafka's ByteBuffer key overload, PostgreSQL NUL text handling and Spring repository translation of domain replay-state exceptions. These were corrected; no architecture, test or coverage gate was disabled. Scheduling/listener switches now allow persistence-only integration tests without background HTTP/Kafka activity. Notification authentication/reconciliation/retention work is still pending before Wave 7 can merge.

### Notification authenticated operations and bounded maintenance

Notification's imported HTTP service clients now share a cached client-credentials identity and bounded connection/read timeouts. The NOTIFICATION_SERVICE role may GET only the existing numeric order detail, reconciliation summaries and service audit endpoints it needs. Other admin reads and writes remain forbidden; public catalog endpoints keep their public behavior. The main realm import now includes this confidential client/role, and the isolated E2E realm derives its fixture from that import rather than creating duplicate clients. Health reads use the services' existing actuator endpoints; optional absent metrics remain unavailable, while transport/authentication/malformed responses explicitly report ERROR.

Reconciliation reports NOT_RUN before its first execution and ERROR on downstream failure; the execution API returns HTTP 502 for a failed run. Inventory compares only reserving order states because confirmation now commits stock. Payment reconciliation reads every page within size 100, validates metadata and applies a bounded page/time budget. Audit aggregation fetches sorted prefixes in pages of at most 100, freezes the upper timestamp, bounds the global window to 10,000 results and returns ERROR/HTTP 502 if any source is unavailable. Source queries now sort by createdAt/id descending. Nullable Instant predicates explicitly cast their null checks because PostgreSQL cannot infer untyped null timestamp parameters. Method-parameter constraint violations return 400 with the existing validation response rather than leaking into the generic 500 handler.

Notification V7 adds database-time ShedLock storage and retention indexes. Both reconciliation schedules are locked across replicas. Processed-event cleanup defaults to 30 days, rejects retention below the seven-day Kafka DLQ window, deletes at most 10,000 markers per transaction and retains identities referenced by active PENDING/REPLAYING failed records. Product-name caching has a size bound and expiry; failed lookups are not cached.

The Framework 7 registry resolves RestClientHttpServiceGroupConfigurer specifically. Both Notification and Inventory now expose that concrete callback interface; a generic HttpServiceGroupConfigurer bean was silently ignored by the registry. Integration tests exercise the registered client, not a manually invoked mock callback, so missing authentication cannot pass unnoticed.

Rollout: provision the notification-service confidential client, NOTIFICATION_SERVICE realm role and matching secret in existing live realms before deploying Notification. Startup realm imports do not update existing realms. Supply NOTIFICATION_SERVICE_CLIENT_SECRET to both Compose Keycloak and Notification; Kubernetes references the required notification-service-auth Secret's client-secret key and the same external Keycloak token hostname as Inventory. Missing/invalid credentials fail explicitly instead of reporting a clean reconciliation. NetworkPolicy allows the necessary Notification HTTP reads without granting it Inventory/Payment gRPC access. No live realm or production secret has been changed by this work. Gateway now forwards reconciliation and global audit requests to Notification, category management to Product, and the explicit /api/v1/admin/products/audit-logs alias to Product's existing /api/v1/admin/audit-logs. The global audit path remains the cross-service aggregator; the product-specific alias avoids shadowing it.

Full service verification completed sequentially on local Docker; exact commands and counts follow. Earlier targeted Notification verification passed 116 unit + 6 integration tests and its coverage gate. Admin type-check, 87 unit tests, production build and read-only formatting checks passed. An isolated ephemeral Keycloak 26.1.4 imported the committed main realm with environment-only secrets: both service identities received exactly their intended single realm role and rejected incorrect secrets with HTTP 401. The smoke container was stopped/removed and no persistent realm was changed.

The new full Order run exposed cross-class data contamination: PendingPaymentRecoveryIT's second worker call picked up a different class's due order (the log shows order 51 followed by order 14), rather than retrying its own order early. That suite now owns an isolated PostgreSQL container; the full rerun passed. Order/Inventory still emit Surefire's last-ditch fork-exit warning after successful tests. The Inventory dump shows SpringApplicationShutdownHook waiting for lifecycle shutdown while a gRPC health scheduler waits for a Hikari connection; shared singleton Testcontainers are registered as destroyable beans in multiple cached contexts. This narrows the remaining shutdown investigation but does not establish a verified lifecycle fix. Production shutdown behavior has not been changed to mask the warning.


Executed on JDK25 with background Java compilation suspended/resumed during service verification:

- `./mvnw clean install -pl :common-lib -DskipE2ETests` — success, 48 unit tests and coverage.
- `./mvnw clean verify -pl :order-service -DskipE2ETests` — success, 180 unit + 56 integration tests and coverage.
- `./mvnw clean verify -pl :inventory-service -DskipE2ETests` — success, 75 unit + 49 integration tests and coverage; registered expiry client authentication is exercised through Spring's actual registry.
- `./mvnw clean verify -pl :payment-service -DskipE2ETests` — success, 42 unit + 34 integration tests and coverage.
- `./mvnw clean verify -pl :product-service -DskipE2ETests` — success, 85 unit + 58 integration tests and coverage, including the restricted operations role. This validates the authentication/audit subset; Product findings 1–9 remain pending.
- `./mvnw clean verify -pl :notification-service -DskipE2ETests` — success, 116 unit + 21 integration tests and coverage. Real HTTP tests verify cached bearer credentials, Unicode query filters, payment paging, bounded timeouts and HTTP 502 on failed reconciliation/audit. PostgreSQL tests verify distributed scheduler exclusion and retention of active failed-record identities. Existing real Kafka DLQ capture/replay suites pass.
- `./mvnw clean verify -pl :api-gateway -DskipITs -DskipE2ETests` — success, 57 tests and coverage. Actual HTTP forwarding tests verify service selection, query preservation, product-audit path rewriting and authentication. The routing fixture mocks only the Redis rate limiter; production remains fail-closed on Redis outage.
- `./mvnw checkstyle:check -pl :common-lib,:order-service,:inventory-service,:payment-service,:product-service,:notification-service` and `./mvnw checkstyle:check -pl :api-gateway` — passed; existing naming warnings remain.
- Admin type-check, 87 unit tests, build and changed-file Prettier checks — passed. Both Compose configurations and Kustomize base/production/ingress-tls — passed.

Intermediate targeted verification first caught a test JWT bean collision, then the ignored generic HTTP-service customizer, nullable timestamp SQL failure and missing method-validation exception mapping. Those causes were corrected. A selected Order integration suite passed its assertions but covered 79% of the service; the full suite then exercised the required 80% gate without disabling it. The full Order recovery failure described above was isolated to shared fixture data and corrected with a private test database. CI on the prior head passed every check after retrying the buf installer job, which had failed on GitHub's unauthenticated download-API rate limit. CI for this new subset still needs to run after push. The final whole-reactor Wave 7 run, full-stack E2E and live realm provisioning have not been performed for this subset.


### Product payloads, concurrent edits and cache clearing

Product live writes and demo indexing now use one complete snapshot mapper, including nullable rating. Editing a product no longer emits a snapshot that wipes its search rating. UpdateProductRequest accepts an optional nonnegative version; existing clients may omit it. Product detail adds version without removing existing fields, and update flushes before creating its response so that the returned version can be used for the next edit. A stale supplied version rejects the write before mutation/outbox creation; Hibernate still detects concurrent writes that read the same version. Product-specific advice runs before the common generic handler, maps optimistic conflicts and PostgreSQL's products_sku_key/23505 race to HTTP409, leaves unrelated integrity failures as server errors, and uses the current trace instead of hard-coding no-trace. Database paths/SQL are not exposed in API messages.

Redis clearing uses SCAN in batches of 1,000. Detail and search request synchronized loading through Spring's cache API. The concurrent loader regression uses the local Spring cache implementation; this does not claim distributed single-flight across replicas with the nonlocking Redis writer. Existing Redis cache/invalidation integration tests passed.

Executed on JDK25, with the background Java compiler suspended/resumed:

- ./mvnw clean verify -pl :product-service -DskipE2ETests — passed, 89 unit + 61 integration tests and coverage. Three new real HTTP/PostgreSQL tests verify usable response versions, stale-write rejection without a new outbox row, rating retention in the persisted update snapshot, two editors sharing one version, and two creations sharing one SKU. Exactly one concurrent create commits a product/event. Existing contract, search, cache, image and CRUD suites passed.
- After adding the concurrent local cache-loader regression, ./mvnw clean test -pl :product-service — passed, 90 unit tests. No production code changed after the preceding full verification.
- ./mvnw checkstyle:check -pl :product-service — passed, existing naming warnings remain.

This subset has no Flyway, Avro or infrastructure changes. Product findings1/2/4/5/6/7, final Wave7 verification and Waves8–9 remain outstanding.


### Product search bounds and failure classification

Search rejects a result window above 10,000 before contacting either data source. PostgreSQL fallback now requires a recognized network connection/read/pool timeout, closed connection or unreachable host in the cause chain. Mapping, authorization, missing-index and other application/query failures propagate instead of becoming apparently successful degraded results. TLS/certificate failures are not reclassified as ordinary unavailability. Existing reduced PostgreSQL filter semantics are unchanged.

./mvnw clean verify -pl :product-service -Dit.test=ProductSearchIT -DskipE2ETests passed on JDK25: 93 unit + 12 selected integration tests, with the coverage gate enabled and passed. Tests cover nested connect/read-timeout failures, mapping/HTTP401 propagation without a PostgreSQL query, pre-query deep-window rejection, and HTTP400 through the real cached search endpoint.


### Product image persistence and transaction boundaries

GET /images/** is public at Product and Gateway, and Gateway forwards it to Product. Image writes remain on ADMIN endpoints; anonymous writes to the public asset path remain rejected. The normal Gateway rate limiter remains enabled. Product detail cache eviction now covers upload/delete/reorder, and the Redis cache manager is transaction-aware so rollback does not evict or publish uncommitted cache changes. File deletion runs only after the database commit. Newly uploaded files are removed after rollback, including a transaction failure after the service method returned; an unknown completion outcome retains the files.

Compose app and E2E stacks mount a named product-images volume at the configured /tmp/robomart-images path. Although that mount path remains backward-compatible, the files now survive replacement of the app container. New upload URLs target the externally reachable Gateway (localhost:8080, isolated E2E localhost:18080, or api.tuanpm.xyz in K8s). The existing prebuilt image already creates this directory owned by UID1001. A real build and a temporary named-volume smoke test verified a UID1001 container can write and a replacement container can read the same marker; only the uniquely named smoke volume was removed afterward.

Kubernetes adds a 2Gi product-images PVC. Base and ingress-tls require ReadWriteMany storage so replicas on different nodes see the same files; provision an RWX-capable storage class/PV before rollout. The existing production overlay explicitly targets a single-node cluster, so its patch uses ReadWriteOnce. Do not reuse that patch for replicas on different nodes. Manifests were rendered; no live PVC was provisioned or application deployed. Wave9 object storage will replace this transitional filesystem dependency.

Before replacing existing Product containers/pods, stop image writers and preserve/copy every existing /tmp/robomart-images tree into the new volume, retaining ownership for UID/GID1001 and checking file counts/checksums. Mounting an empty volume would hide existing image files. Existing database image URLs are not automatically rewritten: reconcile any old localhost/service-only URL prefixes with the configured public Gateway base while retaining their /images/{productId}/{filename} suffixes. Keep the original files/database backup until existing image URLs are verified. No production data or volume was removed by this implementation.

Executed on JDK25 with background Java compilation suspended/resumed:

- ./mvnw clean verify -pl :product-service -DskipE2ETests — passed, 93 unit + 64 integration tests and coverage. New real HTTP/PostgreSQL/filesystem tests verify anonymous GET bytes, cache changes after upload/deletion, original files and rows surviving rollback, commit-only file deletion and cleanup of files uploaded inside a rolled-back outer transaction.
- After adding reorder cache coverage, ./mvnw clean verify -pl :product-service -Dit.test=AdminProductImageIT -DskipE2ETests — passed, 93 unit + 8 selected integration tests and coverage.
- ./mvnw clean verify -pl :api-gateway -DskipITs -DskipE2ETests — passed, 57 tests and coverage, including actual anonymous GET image forwarding and rejected anonymous image writes. The routing fixture mocks its Redis rate limiters; production is unchanged.
- Checkstyle, both Compose config checks and Kustomize base/production/ingress-tls — passed.
- docker build -f backend/Dockerfile.prebuilt --target product-service -t robo-w7-product-image-smoke backend — passed; the image/volume smoke does not start Spring or represent a full-stack application test.

After adding the unit regression that a rejected database delete cannot remove the original file, ./mvnw clean test -pl :product-service passed 94 unit tests. Checkstyle passed again for Product/Gateway. Production code was unchanged after the successful full image verification and selected reorder integration verification.


### Product asynchronous outbox delivery

Product V8 persists event UUIDs and expiring UUID delivery claims. Historical rows retain the exact name-based UUID previously derived from product-outbox-{id}, including the MD5 UUID version/variant bits; pending rows and published history are preserved. New rows receive their persisted UUID before delivery. Only the oldest unpublished row per aggregate is eligible, including across concurrent replicas; an unknown/unmappable head remains pending and blocks its aggregate while independent aggregates progress. A stale claim cannot acknowledge/release a new owner.

Publishing occurs outside database transactions/row locks, on a Micrometer-context executor with at most50 in-flight sends and one bounded batch wait (default10s, lease2min). Known failures release claims; uncertain sends retain their leases and stable IDs for retry. Event timestamp is the stored creation time. The cleanup job uses database-time ShedLock and a bounded batch of10,000 old published rows, retaining seven days by default and every unpublished row. Removed repository methods no longer permit the old unfenced poll path. Test-profile background publishing starts after an hour; delivery tests invoke the real poller explicitly. Kafka factories now respect listener auto-startup for persistence-only tests.

Rollout: stop every pre-V8 Product publisher before applying V8/starting new instances; old publishers do not honor leases and could race the new implementation. Kubernetes Product uses Recreate for this transition. Expect a brief interruption and preserve the image-volume migration steps above. Compose replaces its Product container. No outbox history is retired/deleted by V8.

Executed on JDK25 with the background Java compiler suspended/resumed: ./mvnw clean verify -pl :product-service -DskipE2ETests passed96 unit+73 integration tests and coverage. Tests include concurrent aggregate claims, expired-owner fencing, rollback eligibility, poison blocking, independent progress, bounded wait/MDC propagation, release of DB transaction/row locks before send, retention and exact legacy UUID backfill. A real Kafka round trip verifies the persisted UUID in ProductCreatedEvent. Existing catalog/image/search/contract suites passed. ./mvnw checkstyle:check -pl :product-service passed; no forced fork-exit warning occurred in this run.

Product findings1/2/5 remain pending before final Wave7 verification/merge.


### Product index lifecycle, authoritative projection and recovery

Product now creates products_v1 from checked-in Elasticsearch settings/mapping JSON before listeners start, then attaches the products-search alias. The old concrete products index remains untouched; choosing a distinct alias avoids deleting an existing index merely to reuse its name. Existing unexpected V1 mappings or alias targets fail closed and require an explicit index-version migration. Startup validates keyword brand, scaled_float price (factor100), Vietnamese-folded name, deletion/version fields and the mapping schema marker. Writes require an alias, so a missing alias cannot silently auto-create a dynamically mapped concrete index.

A new index bootstraps current PostgreSQL snapshots in bounded pages. Each completed page persists its last ID in mapping metadata; startup/retries resume that checkpoint after a crash. Readiness remains out of service while initial backfill is incomplete. Initialization retries periodically and rechecks a ready index once per minute. Recognized connection outages keep the existing reduced PostgreSQL search tier available; mapping/auth/configuration failures never pretend that initialization succeeded. Elasticsearch connect/read timeouts are bounded to1/2seconds. The index mapping includes Vietnamese đ/lowercase/asciifolding analysis, explicit keyword/filter fields, dates and scaled price.

All three product event topics now trigger a fresh authoritative PostgreSQL read, including inactive rows. Detached snapshots leave their read transaction before Elasticsearch I/O. Product.version+1 is sent with strict external versioning (Elasticsearch requires a positive version while JPA starts at0). Only an actual version-conflict response is acknowledged as stale/duplicate. Other failures propagate to retry/DLT. This also handles historical events without inventing a new Avro version field: old event bodies cannot overwrite newer database state. Deletion writes a permanent logical tombstone with its source version. Alias and query filters hide it; no physical-delete version-GC window can resurrect it. Retain source soft-deleted rows/tombstones; database restore/index-version cutovers require deliberate reconciliation.

ADMIN POST /api/v1/admin/products/reindex accepts afterId>=0 and size1..100. Each retryable page reads source snapshots, writes within a5second processing budget, and returns processed/nextAfterId/hasNext. Continue with the returned cursor until hasNext=false; retrying a failed page cannot regress already indexed versions. It includes soft-deleted rows, so it also restores tombstones. Customer/Notification identities cannot invoke it. This is an explicit bounded maintenance job interface, not a claim that one page rebuilt the entire catalog.

Product consumers now use exponential retries (500/1000ms) and broker-acknowledged per-topic .DLT recovery. Malformed original value bytes remain bytes. The three DLT topics are application-provisioned with3partitions and configurable PRODUCT_KAFKA_DLT_REPLICATION_FACTOR (default1, matching the current single-broker setup); broker-selected DLT partitions avoid assuming the source partition count. Standard headers retain source coordinates/consumer group. Provision topic creation/write permissions or pre-create these topics when using broker ACLs. Compose/E2E/K8s explicitly enable index initialization and set the current replication factor. After correcting a projection/DLT outage, run the paged reindex job from PostgreSQL; never mark a failed record processed without its effect.

Rollout preserves the earlier Product Recreate requirement: stop old unfenced publishers/old index consumers before V8 and the new projection. The new alias bootstraps from the owning database after cutover; large catalogs remain unready until the checkpointed backfill completes. No old Elasticsearch index, document history or production volume was removed by this code. Keep a verified database/index backup. If V1 was prewarmed while old writers still ran, reindex all pages after stopping those writers before admitting traffic; a completed marker is not proof that later writes were captured by an older consumer. Future index versions need an explicit alias cutover rather than overwriting V1's mapping.

Executed on JDK25 with the background Java compiler suspended/resumed:

- ./mvnw clean verify -pl :product-service -Dit.test=ProductProjectionIT,ProductIndexConsumerIT -DskipE2ETests — passed103unit+6 selected integration tests and coverage.
- ./mvnw clean verify -pl :product-service -Dit.test=ProductDltIT -DskipE2ETests — passed103unit+2 selected integration tests and coverage. Actual Kafka tests verify three source-read attempts, a decodable Avro DLT record/source header, no false processed marker, and exact malformed value bytes.
- After adding the bootstrap-resume integration regression and final mapping guards, ./mvnw clean verify -pl :product-service -DskipE2ETests — passed103unit+79 integration tests and coverage. Actual Elasticsearch/PostgreSQL/HTTP tests cover checked mapping/alias, retained legacy index, accentless Vietnamese matching, price cents, bootstrap checkpoint resumption, role/size limits, reindex retries, delayed snapshots and cross-topic events after deletion. Existing images/search/cache/contracts/outbox suites pass.
- ./mvnw checkstyle:check -pl :product-service — passed; Compose app/E2E config and all three Kustomize renders — passed.

Kafka integration fixtures explicitly own listener start/stop. Other Product test contexts leave listeners stopped; fixture cleanup removes test documents from the physical test index while preserving mapping/alias, including logical tombstones. No application mapping/index is deleted during ordinary startup. All Wave7 implementation findings are now implemented; final reactor/frontend/CI verification and isolated E2E/chaos still remain before merge.


### Wave 7 boundary verification and coordinated cutover

On 2026-10-27, JDK25 `./mvnw clean verify -DskipE2ETests` passed the whole reactor and configured coverage gates: Maven execution summaries report 691 unit tests and 282 integration tests (973 total), with no failures/errors/skips in executed suites. E2E was intentionally excluded. `./mvnw checkstyle:check` passed. The Order, Inventory and Payment forks still reported delayed shutdown after successful tests; the shared-container lifecycle investigation is tracked for Wave 8's fixture work rather than disabling shutdown or coverage checks.

In frontend/, `npm ci`, `npm run type-check --workspaces --if-present`, `npm run test:unit --workspaces --if-present -- --run`, `npm run build --workspaces --if-present`, and read-only Prettier checks for both app source trees passed. Customer ran240 tests and Admin87 (327 total). Proto `buf lint` and `buf breaking --against '../../.git#branch=main,subdir=backend/proto'` passed. Compose core/app and E2E configuration plus Kustomize base/production/ingress-tls rendered successfully.

The rebuilt isolated stack passed full checkout, authenticated reconciliation/audit, confirmation stock commit, refunded cancellation and the 500ms Inventory latency assault. Its first payment-outage run exceeded the existing60second recovery bound: breaker-denied attempts inherited an additional30second provider backoff immediately before the breaker reopened. All five original orders eventually recovered and cleanup refunded them; that run is a failed chaos result, not a pass. A breaker-denied attempt now rechecks within5seconds (or the configured shorter delay); real provider failures retain the configured backoff, idempotency identity and uncertainty hold. Added database regression verifies no charge/release RPC is sent while OPEN and recovery reuses the same key after closing. Fresh verification is required after this correction.

Order and Inventory now also use Recreate, since old recovery/lifecycle writers ignore the new budgets, claims and reservation transitions. Coordinate the six stateful application cutovers rather than relying on independent Deployment ordering: drain checkout/cancel/cart/image writes, stop old Order/Inventory/Payment/Cart/Notification/Product writers and listeners, then apply the owning schemas and start compatible versions. Keep the gateway write traffic drained until dependency readiness, reservation/payment consistency, outbox delivery and image reads are checked. Per-service Recreate alone does not coordinate dependencies. Rollback to old writers against migrated data is unsafe without an independently verified restore/compatibility plan.

Before admitting the new applications, provision the two confidential Keycloak clients/least-privilege roles and matching Inventory/Notification Kubernetes secrets in the existing realm, and copy existing Product files into the prepared persistent volume with verified ownership/checksums. Startup realm imports do not update an existing realm. Main triggers CD image/tag updates, and the checked-in ArgoCD application automatically syncs production; therefore these are prerequisites to merging into an actively deployed main. No live realm, cluster, image tree or production volume was modified in this session. The locally selected Kubernetes context belongs to a different application and was not used for this rollout.

Base Compose Notification now supplies the same downstream service DNS URLs as E2E/Kubernetes; localhost defaults would otherwise break its new authenticated aggregation clients inside the container.


Post-fix boundary result: `./mvnw clean verify -DskipE2ETests` passed 691 unit + 283 integration tests (974), all coverage gates and Checkstyle. Rebuilt isolated E2E/chaos passed 3 tests with no failures/errors/skips, preserving the 60-second restart recovery assertion. Read-only cleanup checks found all 7 new orders cancelled, all 7 payments fully refunded and all 7 reservations released, with 7 movements each of RESERVE/COMMIT/RELEASE. The stack was stopped without removing volumes; unrelated MySQL remained running. CI passed every check on implementation head 75b83d2. See [the final Wave 7 validation report](../_bmad-output/test-artifacts/wave7-correctness-validation-2026-10-27.md) for exact commands, coverage, failed-first-run evidence and deployment limits. At this verification snapshot, merge was waiting for live cutover status; the merged status above records its resolution.


### Wave 8 platform foundation

Started from updated main at Wave 7 squash 7f9167b. User confirmed auto-deploy is not currently running; no live deployment or Kubernetes mutation is part of this continuation.

Checkstyle 12.3.1 is explicitly provided to the Maven plugin and check is bound to validate in the inherited active build. The existing rule severity is unchanged; validate passed all 14 modules with the modern parser and existing advisory warnings. The module configuration requires no removed-rule substitutions. Version reference: https://checkstyle.org/release-notes.html#Release_12.3.1.

IntegrationTest now supplies only common Spring test behavior. ProductIntegrationTest starts PostgreSQL/Kafka/Elasticsearch/Redis; CartIntegrationTest starts Kafka/Redis; InventoryIntegrationTest starts PostgreSQL/Kafka. Existing Order/Payment/Notification tests retain their explicit resource selections. Shared JVM-owned containers register connection properties rather than container beans, avoiding Boot closing shared infrastructure with one cached context while other contexts still use it. Testcontainers/Ryuk owns final process cleanup; reuse is not required. Class-owned isolated containers retain their context/class lifecycle. This specifically addresses the Wave 7 teardown warning without reducing shutdown timeouts or disabling health checks.

OSIV is disabled in every JPA service; all six servlet services enable virtual threads. Main Hikari pools default to maximum 10/minimum 2 with a 3-second acquisition timeout, configurable through DB_POOL_MAX_SIZE/DB_POOL_MIN_IDLE/DB_CONNECTION_TIMEOUT_MS. Payment's independent mock-provider pool remains 2 with a 1-second acquisition timeout and semaphore 2. These are explicit starting limits, not load-test sizing claims. Native gRPC does not inherit the servlet executor; Order/Inventory/Payment use Boot's GrpcServerExecutorProvider with a context-propagating managed virtual-thread executor. Inventory's real gRPC integration test captures the service execution thread and verifies isVirtual(). Boot owns gRPC observations; duplicate custom TracingConfig was removed.

Removed hard-coded active dev profiles; host development now needs an explicit dev profile, while Compose/K8s use their supplied connection settings and E2E already selects dev/demo explicitly. Locale-independent role normalization is verified under tr-TR. JaCoCo no longer excludes config packages, including security configuration. Gateway no longer admits Keycloak as a browser CORS origin. Anonymous rate limits use the socket peer with framework forwarding disabled, so arbitrary Forwarded/X-Forwarded-For headers cannot create new buckets. Behind an ingress, anonymous users share the ingress peer bucket; introducing trusted proxy ranges requires an explicit, verified network boundary rather than a global trusted-hop count.

The shared error response extends ProblemDetail with RFC 9457 status/title/detail/instance and code/traceId properties, while retaining error.code/error.message/error.details/timestamp for existing frontend readers. GlobalExceptionHandler extends ResponseEntityExceptionHandler, preserves framework response headers and committed-response behavior, and handles authorization denials without exposing internal details. Product's local advice uses the same response type. Standard missing headers, unreadable JSON, type mismatch, unsupported media and method errors retain their 4xx statuses. Initial MVC tests expected an explicit about:blank type, but Framework 7 correctly omits that RFC default; the assertions now validate the actual required status/detail/content type and compatibility fields.

Executed JDK25 validation for this foundation (not the complete Wave 8 gate):

- ./mvnw validate — all 14 modules passed with Checkstyle 12.3.1.
- ./mvnw clean install -pl :test-support -am -DskipTests -DskipITs -DskipE2ETests — dependency installation passed; no test claim.
- ./mvnw clean verify -pl :order-service -DskipE2ETests — 180 unit + 57 IT and coverage passed, no forced fork-exit warning.
- ./mvnw clean verify -pl :inventory-service -DskipE2ETests — 75 unit + 49 IT and coverage passed, including the real virtual-thread assertion; no forced fork-exit warning.
- ./mvnw clean verify -pl :common-lib,:security-lib,:api-gateway -DskipITs -DskipE2ETests — Common 48 and Security 16 passed their expanded coverage gate; Gateway initially failed due to a misplaced YAML property. The property was corrected at the root server section.
- ./mvnw clean verify -pl :api-gateway -DskipITs -DskipE2ETests — 58 tests and coverage passed after that correction.

./mvnw clean verify -pl :common-lib -DskipITs -DskipE2ETests passed 54 tests and coverage after the ProblemDetail change, including six real MVC status/content-type/envelope/header cases.

Kafka/observability, remaining runtime validation, image/JVM/AOT work, resilience, null-safety and JWT hardening continue before a Wave 8 PR. No Wave 8 merge or completed-wave claim is made by these focused results.


### Wave 8 Boot-managed Kafka, tracing and JWT boundaries

Kafka services now explicitly depend on spring-boot-starter-kafka (the previous direct spring-kafka dependency did not provide Boot 4's Kafka auto-configuration). No application constructs a producer or consumer factory. Spring Kafka properties retain acknowledgement, poll and Schema Registry bounds; Boot owns factories, templates, admin clients and observation settings. Small producer customizers install Avro/raw-byte dispatch serializers without discarding configured interceptors/security/connection properties. Framework-supported listener customization retains retry/DLT behavior. Notification's terminal listener overrides its deserializers at the KafkaListener declaration; container-default properties alone were overwritten during listener creation and the initial routing test caught that error. Its terminal error handler treats every archive failure as retryable, with indefinite exponential backoff capped at 30 seconds, so conversion defects cannot acknowledge an unarchived record either.

Boot's ECS structured console logging replaces the custom logback/logstash configuration in every application, including Gateway. Micrometer remote baggage fields correlationId and X-Correlation-Id propagate through HTTP/Kafka and correlate into their corresponding MDC fields. The legacy HTTP filter and batch consumer/producer interceptors are removed; absent correlation baggage is not fabricated, while normal trace IDs remain available. Incoming legacy X-Correlation-Id remains a baggage field; a generated legacy response header is no longer part of this instrumentation. Frontend does not read that header. Outbox context snapshots continue to propagate scopes. Native Boot gRPC observation remains authoritative.

Test-support uses the production OTel bridge rather than Brave. OTel's Kotlin exporter and Testcontainers' duct-tape requested incompatible legacy JetBrains annotation versions; dependency management aligns them to 17.0.0 without relaxing convergence. AutoConfigureTracing(export=false) also disables propagation in Boot 4's test context. The real propagation test therefore enables tracing and disables only the OTLP exporter (management.tracing.export.otlp.enabled=false), avoiding external reporting while preserving the actual propagators. A Kafka poll processes queued records with distinct and then absent baggage; assertions verify per-record baggage/MDC, real trace scopes, no leaked caller MDC and a configured producer interceptor invoked three times. HTTP asserts baggage/MDC and a real trace ID on a virtual thread. Version references: https://docs.spring.io/spring-boot/reference/actuator/tracing.html and https://docs.spring.io/spring-boot/api/java/org/springframework/boot/micrometer/tracing/test/autoconfigure/AutoConfigureTracing.html.

All JWT resource servers now validate the canonical issuer and robomart-api audience while fetching JWKs through an independent explicit URI. Compose uses the public localhost:8180 issuer and Docker-internal keycloak:8180 key endpoint; E2E uses public localhost:18180 with the same private key endpoint; K8s retains auth.tuanpm.xyz as its canonical issuer. No token issuer is rewritten to an internal hostname. Cart still delegates identity to the validated Gateway and retains anonymous guest carts; this change does not introduce a new Cart JWT resource server. Notification no longer creates a JWK-only decoder: Boot's validating decoder is shared by HTTP and STOMP. The main and test realms attach an access-token-only audience mapper; ID-token audience semantics and least-privilege roles are unchanged. Existing live realms must receive the mapper before enabling audience enforcement; no live realm was changed.

E2E now has a guarded audience-only reconciliation script after Keycloak readiness and before app startup. It verifies the exact isolated project/service labels and loopback port, updates only the five known fixture clients' audience mapper, keeps credentials/tokens in memory, and preserves existing realm/order/image data. This avoids deleting persistent test volumes just because startup import does not update a realm. Python compilation, shell syntax, Compose core/app and E2E configuration, and all 3 Kustomize renders passed; the updated reconciliation script still awaits execution in the final E2E stack.

Executed JDK25 checks for this continuation:

- Dependency installation for common/security/proto/events/test-support passed after convergence alignment; tests skipped for this installation only.
- ./mvnw clean test -pl :product-service,:cart-service,:inventory-service,:order-service,:notification-service -DskipE2ETests — 519 unit tests passed before later JWT/test additions.
- ./mvnw clean verify -pl :product-service -Dit.test=ProductDltIT,PlatformBaggageIT -DskipE2ETests — the Product DLT cases passed, but early baggage fixture runs failed (stale auto-configuration metadata, a self-referencing test listener topic, then disabled test propagation). Those causes were corrected; none is counted as passing full verification.
- ./mvnw clean verify -pl :product-service -Dit.test=PlatformBaggageIT -DskipE2ETests — 103 unit + 2 real HTTP/Kafka IT passed, including coverage.
- ./mvnw clean verify -pl :common-lib,:security-lib,:api-gateway -DskipITs -DskipE2ETests — 41 Common + 16 Security + 59 Gateway tests and all expanded coverage gates passed. Gateway uses a local JWK server and real signed tokens to reject wrong/missing issuer, wrong/missing audience, expired tokens and invalid signatures, while accepting the valid token. Zero JWK requests occurred at startup and before the first bearer token.
- ./mvnw clean verify -pl :notification-service -DskipE2ETests — 116 unit + 22 IT and coverage passed after correcting terminal deserializer selection. Tests exercise full Avro/raw malformed-byte archive, replay/deduplication, and archive failure twice followed by successful persistence with no lost record. No forced fork-exit warning occurred.

Full final reactor, remaining runtime checks and E2E will be recorded after resilience, null-safety and image/JVM changes. No completed Wave 8 claim is made by these focused results.


### Wave 8 resilience primitives

Order's gRPC retry now uses Framework 7 Retryable with the existing status classifier as a MethodRetryPredicate: only UNAVAILABLE/RESOURCE_EXHAUSTED/ABORTED retry, at most two retries after the first attempt, with 1s/2s delays by default. Business rejections and uncertain deadlines retain their existing treatment. The dedicated Spring RetryAnnotationBeanPostProcessor appends retry inside existing circuit-breaker advice; Spring's default before-existing-advisors behavior would put it outside fallback and recreate the old no-retry bug. Resilience4j retains circuit breaking/fallback, and its unused retry configuration is removed. The focused production-proxy regression passes 10 cases, including exactly 15 RPC attempts for 5 exhausted retryable checkouts, 1 attempt per business rejection and zero calls through OPEN.

PaymentProviderCalls is one shared Framework 7 ConcurrencyLimit with REJECT policy and limit 2 for both charge/refund transactions. MockPaymentGateway maps rejected invocations to the existing transient busy result, preserving the bounded independent provider pool and receipt/idempotency flow. Framework advice releases permits on completion or exception; the manual semaphore is removed. A proxy-based concurrency regression holds two operations, verifies immediate rejection of the third, releases both and checks permit reuse after failure. ./mvnw clean verify -pl :payment-service -DskipE2ETests passed 44 unit + 34 IT and expanded coverage, with no forced fork-exit warning. An initial mapping regression incorrectly exercised the deprecated direct simulation overload; it was corrected to the actual idempotent provider path before that successful run.

Gateway adds one circuit per backend across its HTTP routes. Transport failures/open circuits forward to a 503 RFC 9457 response retaining the error envelope; upstream HTTP statuses/bodies remain unchanged, including an aggregation ERROR response. WebSocket upgrades retain their native lifecycle. HTTP timeouts remain 2s connect/30s response; the Cloud circuit library's default one-second TimeLimiter and automatic bulkhead are disabled explicitly. No gateway Retry filter is introduced. Default circuits use a 20-call window/minimum 5, 50% threshold, 10s open period and 3 half-open probes; service-specific properties can override these. A slow checkout probe exceeds one second and still succeeds under the existing HTTP bound. Reference: https://docs.spring.io/spring-cloud-circuitbreaker/reference/spring-cloud-circuitbreaker-resilience4j/circuit-breaker-properties-configuration.html.

The Cloud 5.0.3 starter transitively includes resilience4j-spring-boot3, whose compatibility verifier correctly rejected Boot 4. That dependency is excluded and replaced with the existing managed resilience4j-spring-boot4 adapter; the verifier is not disabled. ./mvnw clean verify -pl :api-gateway -DskipITs -DskipE2ETests passed 62 tests and coverage, including real transport failure/open/half-open routing, preserved 502 body and the slow checkout bound. The first incompatible-adapter run is a failed intermediate run. Gateway tests also use Boot's tracing test module to avoid external reporting by default.

The full Order recovery suite and final reactor/E2E remain required after the remaining platform work. No Wave 8 PR/merge has occurred.


### Wave 8 null-safety compiler gate

Common and Security production packages are explicitly JSpecify NullMarked. Nullable contracts describe real absence in DTO payloads, audit metadata, pre-persist entity fields, authentication and exception causes. Framework exception overrides match Spring's nullable body/return contracts; missing exception/validation messages receive stable safe defaults. Complex GrantedAuthority values without a string role are ignored, with a regression test.

Both modules run NullAway 0.13.8 through Error Prone 2.51.0 during production compilation, at ERROR severity, with JSpecify mode. No blanket NullAway suppressions were introduced. Test compilation remains separate so intentional invalid-input tests exercise runtime behavior. Maven JVM exports in backend/.mvn/jvm.config support the javac plugin on JDK25. References: https://github.com/uber/NullAway/wiki/JSpecify-Support and https://github.com/uber/NullAway/releases/tag/v0.13.8.

Executed ./mvnw -B -ntp clean verify -pl :common-lib,:security-lib -DskipITs -DskipE2ETests: Common44 + Security17 tests and coverage passed. A temporary production source in a marked package deliberately returned null: compilation failed with the expected NullAway error. The probe was removed and the clean positive verification repeated successfully. This demonstrates that the compiler gate is active, beyond merely declaring annotations.


### Wave 8 aligned application images and isolated training

A single Python renderer now owns all9 existing Dockerfiles. Source builds retain both the backend-root multi-target path and repository-root per-service CD paths, without changing workflows. All source builders copy Checkstyle configuration, Maven JVM exports and every reactor POM, including the new build-support and E2E modules. Runtime images use the same digest-pinned multi-platform Temurin25 JRE index, UID1001, curl health checks, correct management/gRPC ports, Boot extracted dependency/application layers, compact object headers and MaxRAMPercentage75. Obsolete container-support/entropy flags are removed. The E2E-only fourth application Dockerfile is removed: isolated E2E now builds the actual prebuilt production targets. Its build script rejects generated-file drift.

The build-support module supplies an explicit aot-training profile on an isolated Spring source without component scanning, imports or auto-configuration. Its configuration is profile-guarded against normal application scans. Application configuration locations are ignored during training; no business beans, Flyway, schedulers, consumers, seeders, exporters or provider clients are started. BuildKit also disables networking for that RUN. The actual application extracted classpath and exact runtime JRE are used to create application.aot; the real service main runs from the thin JAR with that cache. This warms only core Spring classes and does not claim measured full-service startup improvements. Context exit by itself would not make normal service startup safe. References: https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html and https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html.

Executed before final reactor completion:

- python3 backend/scripts/render-dockerfiles.py --check — all9 files match; shell syntax, Compose core/app/E2E and all3 Kustomize renders passed.
- ./mvnw -B -ntp clean verify -pl :build-support -DskipITs -DskipE2ETests — minimal-context isolation test and coverage passed. Its first assertion omitted Boot's infrastructure metadata-reader bean; the allowed infrastructure set was corrected without admitting application beans.
- ./mvnw -B -ntp clean install -DskipTests -DskipITs -DskipE2ETests — all15 modules packaged/installed. Tests intentionally skipped for image inputs. An earlier non-clean install found stale coverage data after IDE compilation; the clean install avoids reusing that data and is not counted as test verification.
- docker build --progress=plain -f backend/Dockerfile.prebuilt --target product-service -t robo-w8-product-aot backend — passed; network-disabled training produced a cache. Isolated reuse reported Opened AOT cache, mapped regions and Using AOT-linked classes:true, including a512MiB container with192MiB heap.
- docker build --progress=plain -f infra/docker/product-service/Dockerfile -t robo-w8-product-source . — source packaging, active NullAway and AOT training passed. The later profile guard and final service images still require their final builds.

The initial full reactor stopped at Order's Pact provider: its committed404 contract still required application/json, whereas RFC9457 correctly selects application/problem+json. The legacy body fields matched. Both consumer declaration and provider pact now agree on the error media type; Notification's consumer test invokes the actual OrderServiceClient and verifies successful typed decoding and a404 returning null. ./mvnw -B -ntp test -pl :notification-service -Dtest=NotificationOrderConsumerPactTest passed2 cases. The whole reactor is being repeated after that correction, with no weakened status/body matchers or skipped tests.


### Wave 8 final reactor gate

After the Pact media-type and reactive-adapter corrections, ./mvnw -B -ntp clean verify -DskipE2ETests passed all15 modules:698 unit +286 IT=984, zero failures/errors/skips. Counts agree between Maven execution summaries and actual XML testcase elements (some nested-class XML suite header counts are smaller). All configured coverage gates passed, and no forced-fork-exit warning remained. ./mvnw -B -ntp checkstyle:check passed all15 modules. Proto buf lint/breaking against main passed; all3 real Avro compatibility IT passed in the reactor. Both frontend apps passed type-check/build/format and327 unit tests. See the Wave8 validation report for the per-module table. Image/E2E and CI completion still remain before merge.


The actual Gateway image smoke then exposed a Cloud5.0.3 blocking-adapter annotation referencing a Boot3 metrics class. The reactive adapter has no such reference. Gateway now excludes only the unused blocking auto-configuration; its reactive factory and the Boot4 Resilience4j adapter remain enabled. A new regression asserts one reactive factory, no blocking factory and a successful circuit call recorded by Micrometer. Focused Gateway verification passed63 tests/coverage. Rebuilt source/prebuilt Gateway images reached liveness UP, loaded AOT-linked classes and had no Boot3 introspection warning. The final repeated reactor and Checkstyle passed after this configuration correction, with698 unit+286 IT, expanded coverage and no Boot3 introspection or forced-fork-exit warnings.


### Wave 8 retained-data E2E findings

The first guarded stack start stopped before applications because the retained isolated realm lacked Inventory's Wave7 service client. A one-time guarded fixture provisioner verified the exact Compose labels/loopback Keycloak, created only the missing Inventory client from the committed fixture and its intended service-account grant, and preserved existing identities/secrets/data. The normal audience-only reconciler then completed. Both service identities received canonical-issuer/robomart-api tokens with exactly their intended role; Inventory could read the minimal internal order-status endpoint (200) and could not list admin orders (403). No production realm/cluster was changed. The reconciler remains audience-only and refuses missing clients.

All13 containers became healthy. Read-only probes verified UID1001 and an actually memory-mapped nonempty AOT cache in every application JVM (under Compose's init process). The first E2E run nevertheless failed all3 tests before creating an order: the stack lacked SPRING_ELASTICSEARCH_URIS, and PostgreSQL degraded search mapped lazy images after its repository transaction closed under OSIV=false. Aggressive100ms search polling exhausted anonymous rate-limit tokens and obscured the initial500 with429. No test-created order existed beyond the baseline21.

Product's fallback now opens a read-only TransactionTemplate only around the PostgreSQL query and DTO mapping; Elasticsearch I/O stays outside it. Existing degraded-mode filter semantics and connection-only fallback classification are unchanged. A new real HTTP ProductSearchFallbackIT forces an Elasticsearch connection failure, asserts no database transaction during that I/O, and verifies category/image DTO fields from PostgreSQL with OSIV disabled. Unit cache fixtures now supply the newly explicit transaction-manager dependency. ./mvnw -B -ntp clean verify -pl :product-service -Dit.test=ProductSearchFallbackIT -DskipE2ETests passed103 unit+1 targeted IT and coverage. The initial attempt found the missing transaction-manager mock in the cache-only unit context; it was corrected before the successful run.

E2E explicitly addresses Elasticsearch through container DNS. Its45second search budget is unchanged, with3second polling that respects the actual anonymous token rate. New Product source/prebuilt images and the retained-data E2E stack are being verified before the final post-fix reactor/CI gate.


### Wave 8 isolated E2E and data cleanup completed

After the fallback/Elasticsearch configuration fixes, infra/e2e/stack.sh up passed with all13 containers healthy, and audience reconciliation reported0 updates on repeat. infra/e2e/stack.sh test passed all3 real tests (full flow9.618s, both chaos65.49s, Maven1m16s). The original60second recovery assertion is unchanged: five orders created during Payment outage recover automatically with the same keys and no duplicate charges. Latency injection and asynchronous confirmation, cancellation/refund, audit and reconciliation paths passed.

Read-only post-test SQL attributed only orders22–28 beyond the baseline21: all7 CANCELLED, each payment fully REFUNDED (179.99), every reservation RELEASED, and exactly7 RESERVE/7 COMMIT/7 RELEASE movements. No earlier order was removed. Teardown stopped/removed only the isolated project's containers/network, retained all volumes and left unrelated ivysport-mysql running. The first failing E2E attempt had created no orders. The full post-fallback reactor/CI gates remain before merge.


### Wave 8 final post-fallback gate

The final ./mvnw -B -ntp clean verify -DskipE2ETests passed all15 modules in6m35s after the E2E-discovered fallback fix:698 unit+287 IT=985, zero failures/errors/skips, all configured coverage gates passed. Product includes82 IT with the new real HTTP degraded-search regression. Explicit Checkstyle passed all15 modules; dependency installation then succeeded with tests intentionally skipped and the IDE compiler still suspended. No forced-fork-exit or Boot3 introspection warning remained. All3 isolated E2E/chaos tests had already passed on the same implementation, with verified refunds/stock cleanup. The final validation report consolidates evidence and limits. PR CI and squash merge remain.


### Wave 9 shared outbox extraction

outbox-lib now owns the unchanged PostgreSQL head-eligibility/SKIP LOCKED claim SQL, fencing, published-only10,000-row retention, duration validation and bounded asynchronous dispatcher. Product/Inventory/Order retain their schemas, event mapping and distributed scheduler locks. Persisted Product/Inventory UUIDs and Order's exact legacy UUID-name namespace/row derivation are preserved; no migration or event-ID rewrite is introduced. The library has no Kafka/web starter. CDC is documented as a future, separately designed handover; Kafka Connect is not added.

Executed: ./mvnw -B -ntp clean verify -pl :outbox-lib -DskipE2ETests passed10 unit+6 PostgreSQL IT and coverage, including a deliberately held row lock, old-fence rejection and a10,001-row retention fixture. ./mvnw -B -ntp test -pl :product-service,:order-service,:inventory-service -DskipE2ETests passed358 unit tests. Selected outbox verification passed Product103unit+11IT and coverage; Order180unit+7selectedIT passed its assertions but correctly failed the76% partial-suite module coverage gate. No gate was weakened. Full ./mvnw -B -ntp clean verify -pl :order-service,:inventory-service -DskipE2ETests then passed Order180unit+57IT and Inventory75unit+49IT, both coverage gates, without forced fork exits. Shared dependencies were installed before module-only testing; the image renderer includes the new module.
