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
| Product 1 | Versioned explicit index/mapping/alias, Vietnamese analyzer and scaled price | Pending |
| Product 2 | External versions including durable deletion semantics | Pending |
| Product 3 | Shared complete event payload including rating | Implemented: one snapshot mapper for live edits and demo indexing, preserving nullable rating; unit and real persisted-outbox regression passed |
| Product 4 | Bounded paging and connectivity-only fallback | Implemented: 10,000-result window, network-cause-only fallback, mapping/auth failures propagate; real HTTP deep-page rejection and unit classification tests passed |
| Product 5 | DLT and admin rebuild | Pending |
| Product 6 | Outbox ordering, async send, unknown types and retention | Pending |
| Product 7 | Image route/auth/storage, post-commit delete and cache eviction | Pending |
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
