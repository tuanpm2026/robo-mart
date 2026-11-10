# Isolated Java 25 E2E stack

## Current external Saga fixture

New orders in this fixture use `checkout-saga-service` and return **202/PENDING**. Cart acknowledgement waits for confirmed checkout. A cancellation remains **CANCELLATION_PENDING** until provider closure/refund and Inventory release are confirmed. Legacy embedded orders stay with their original coordinator. Payment and email are mocks; no real payment provider is required.

The Saga owns `saga_db` under a separate non-superuser role. `stack.sh up` idempotently provisions only that database/role and the new restricted Keycloak client/role/mappers against label-verified retained fixtures. Existing application data, user secrets and volumes are retained. The main realm template uses `REPLACE_ME`; only the isolated runtime fixture supplies public test credentials. Never reuse these credentials outside the fixture.

Saga dispatch is off by default in application configuration and explicitly enabled in this fixture. Each worker has four slots, 30-second leases, 5-second RPC deadlines/retry delay, a one-hour phase budget, 120 normal attempts and 12 refund attempts. Intent/command/refund identities survive retries and takeover. Unknown outcomes keep stock; exhausted recovery requires audited ADMIN resume.

Each participant has an independent circuit breaker: five transport failures open it for 30 seconds, followed by one recovery probe. Deterministic business/identity rejections do not count as transport outages. An open breaker defers the same persisted phase and records `breaker_denied`; it cannot authorize compensation, release stock, or reset the recovery budget. There is no extra in-process RPC retry layer.

Run browser and Java E2E **sequentially**. The CI browser workflow now runs both. Java durability tests stop Kafka/Payment/Saga and restore them in `finally`; a test-owned gRPC proxy withholds a real committed Payment reply, kills its dispatching Saga replica and verifies receipt-based recovery on the other replica. The proxy lives only in E2E test sources. Payment gRPC is exposed on loopback port 19095 in this fixture; the proxy temporarily uses host port 19096. Neither is added to production manifests. Worker replicas have no fixed host port conflict.

`stack.sh saga-stop|saga-start` guard the exact single Saga container; `saga-scale-two|saga-scale-one` exercise shared-DB workers. Mutation helpers verify project/service labels and reject arbitrary targets. Do not run these commands while another suite owns a fault window.

The default developer/production admission stays off until participant migrations, `saga_db`, the signed `SAGA_SERVICE` identity and a compatible worker are ready. Rollback switches future admission to embedded; it must keep the external worker running for existing external orders. Preserve the last published release manifest: the next successful CD run must supply all **eight backend digests** from one approved source, including Saga. No previous release digest is invented for the new service.

Metrics include `saga.steps`, `saga.workflows.pending`, `saga.workflows.manual` and `saga.queue.oldest.seconds`, without order/saga ID tags. Prometheus scrapes Saga and loads backlog/manual-review rules. Admin inspection/resume is available through Gateway at `/api/v1/admin/sagas`.

For a guided concurrent checkout exercise, see the [k6 flash-sale experiment](../../backend/k6/README.md).
It creates disposable customers and one stock unit, checks HTTP replay plus provider/stock
evidence, and supports Payment outage/recovery. Run it separately from the Java/browser suites;
it uses this same isolated stack and restores/cancels/refunds its own fixtures.

## Customer browser regression tests

The Customer suite uses Playwright Chromium against this same isolated backend and real
Keycloak OIDC. It creates a separate disposable customer/product per test, restocks only
that product, and cancels its orders before removing the customer and soft-deleting the
product. Existing realm users, carts, business data, and volumes are preserved. Payments
and email remain the existing mocks.

After packaging/building/starting the stack as below, run from `frontend/`:

```bash
npm ci
npx playwright install chromium
npm run test:e2e --workspace=customer-website
```

The runner builds the actual Customer bundle with isolated API/Keycloak URLs into the ignored `node_modules/.cache/robomart-browser-e2e` directory, separate from ordinary `dist` builds, and serves
it on `http://localhost:5173`. Stop any existing Vite server on that port first; reusing an
unverified server is disabled. The suite targets fixed loopback ports 18080/18180 and
never accepts a production base URL. Failed runs retain screenshots/traces under the
ignored `customer-website/test-results/`; the HTML report is in `playwright-report/`.

Covered behavior: a committed order whose HTTP response is lost replays the same order
after reload; purchased cart quantities disappear after reload while later additions in
another authenticated tab survive; cleanup outage/lost acknowledgement preserves the
accepted order and retries cart cleanup; protected order links retain path/query/fragment
with a restored session and after a fresh login. These are real service calls; network
interception drops selected responses or simulates cleanup unavailability only.

The `Customer Browser E2E` workflow runs the suite on relevant pull requests and manual
dispatch. It packages backend apps without tests (Backend CI owns those unit/integration
gates), builds/starts this stack, installs Chromium, runs the browser suite, uploads
failure diagnostics, and tears down containers without deleting volumes. Run browser
tests and `stack.sh test` sequentially: the Java chaos suite deliberately stops Payment.

Checkout recovery keeps only a per-user key, shipping/order payload, and cart snapshot in
sessionStorage; card numbers/CVV are never persisted. An ambiguous retry uses the saved
payload even if the current cart changed. Cart acknowledgement is an authenticated
cart-only operation: Redis atomically consumes matching line generations once per order;
additions remain, while explicit quantity replacements or remove/re-add create a new
generation that an old snapshot cannot consume. Receipts live with the cart's TTL. A cart
cleanup failure retains the saved order receipt for the confirmation page to retry.

## Backend stack operations

This standalone Compose stack uses the project `robomart-java25-e2e-resume`, project-scoped volumes, no fixed container names, and loopback-only alternate ports. It never references developer Compose volumes. Do not merge it with the default Compose file or reuse its volumes for production.

Prerequisites: JDK 25, Python 3, Docker Compose, approximately 8 GiB allocated to Docker, and packaged backend JARs. Finish integration tests before starting this stack so Testcontainers do not compete for memory. Service-owned PostgreSQL databases share one dedicated PostgreSQL container. Memory caps total approximately 7.7 GiB (plus a short-lived128MiB bucket initializer); application JVMs use 192 MiB maximum heaps and 256 MiB maximum metaspace. Startup is sequential. Frontend and monitoring services are not required by these HTTP tests.

From the repository root:

```bash
# Build with JDK 25 first; this command does not run tests.
(cd backend && ./mvnw install -DskipTests -DskipITs -DskipE2ETests)
infra/e2e/stack.sh build
infra/e2e/stack.sh up
infra/e2e/stack.sh ps
infra/e2e/stack.sh test
infra/e2e/stack.sh logs
infra/e2e/stack.sh down
```

`build` generates an ignored `.runtime/robomart-realm.json` from the committed main realm, replacing only Inventory/Notification/Saga secrets with isolated public test fixtures. The main import already contains both service clients/roles. These fixture secrets and all database/admin credentials here are unsuitable for deployment. The generator leaves the source realm unchanged. Application images use the production `backend/Dockerfile.prebuilt` targets, including layered extraction, pinned JRE, compact headers and side-effect-free AOT training. They install `curl` explicitly for healthchecks. The build first checks generated Dockerfiles for drift.

Notification background authentication is enabled by default and explicit in this stack. Its cached client-credentials token permits only the required GET order detail, reconciliation summaries and source audit logs; it grants no general admin/customer writes. Inventory uses its separate `INVENTORY_SERVICE` client to read the minimal internal order-status endpoint for safe reservation expiry. Existing live realms need separately provisioned matching roles, confidential clients and generated secrets; startup realm import does not update them.

Product uses the checked `products_v1` index and `products-search` alias, with checkpointed PostgreSQL bootstrap and authoritative versioned writes. Existing concrete `products` indexes are preserved. The stack stores new images in its own MinIO `minio-data` volume and returns public object URLs on loopback port19002. The old `product-images` volume remains mounted read-only at `/migration/images`; old Gateway URLs redirect after explicit migration. Bucket initialization grants anonymous object GET only. See [image storage/migration](../../docs/product-object-storage.md). Product DLTs are application-provisioned with the single-broker replication factor. Reindex maintenance is paged: POST `/api/v1/admin/products/reindex?afterId=0&size=100` as ADMIN, then repeat using `nextAfterId` until `hasNext=false`; failed pages are safe to retry.

Host endpoints: gateway `127.0.0.1:18080`, Keycloak `localhost:18180`, order `127.0.0.1:18083`, inventory `127.0.0.1:18084`, payment `127.0.0.1:18086`. The Keycloak issuer is `http://localhost:18180/realms/robomart`; internal JWK/token requests use container DNS. Downstream HTTP/gRPC requests use service DNS.

`test` obtains the committed demo-admin JWT and exports `E2E_ADMIN_TOKEN` and `CHAOS_ADMIN_TOKEN` in its subprocess environment. It never prints JWTs or passes them as Maven arguments. It also exports `E2E_BASE_URL`, `E2E_KEYCLOAK_URL`, `E2E_ORDER_URL`, `E2E_INVENTORY_URL`, `E2E_PAYMENT_URL`, `E2E_COMPOSE_PROJECT`, and `E2E_PAYMENT_SERVICE`, and explicitly enables E2E/chaos. Customer and product/inventory fixtures come from the committed realm and Flyway seeds enabled by the `demo` profile for Product and Inventory.

Java E2E probes and chaos commands resolve Docker from `/usr/bin/docker`, `/usr/local/bin/docker`, or `/opt/homebrew/bin/docker`, following symlinks to the executable. They never search `PATH`. For a different installation, export `E2E_DOCKER_BINARY` with an absolute path to a regular executable Docker file before `stack.sh test`; a relative, missing, or non-executable override fails immediately. The executable override does not change Compose project/service ownership checks.

Chaos Monkey starts disabled. Its assault adds latency inside the Inventory service through Spring instrumentation; it does not emulate network or gRPC transport latency. Its watcher/actuator are available only in this isolated stack so tests can enable a measured assault and disable it afterward. Chaos tests must validate container ownership using both Compose project and service labels before stopping Payment. `stack.sh payment-stop` and `payment-start` implement the same ownership guard for diagnosis.

The payment outage test stops Payment before creating five orders and verifies that the scheduled worker automatically confirms those same orders after Payment restarts, without a fresh checkout to trigger recovery. The unchanged shared 60-second recovery budget includes participant startup and scheduled external recovery. Each original order must have exactly one completed payment, one completed provider charge operation, one finalized payment intent, one inventory reservation movement and one committed stock movement, one immutable Saga intent for each forward step, and one confirmation event/history entry. The test registers resumed orders for cancellation, refund, and stock-release cleanup as soon as they are observed.

For legacy EMBEDDED orders only, the old Order worker is enabled through `saga.payment-recovery.enabled`. Its defaults are `check-interval-ms: 5000`, `initial-delay-ms: 5000`, `retry-delay: 30s`, and `batch-size: 20`. The initial hold and each provider failure defer the next attempt by 30 seconds. A breaker-denied call sends no RPC and rechecks within five seconds (or the configured shorter retry delay). Each scan claims due pending orders with `FOR NO KEY UPDATE SKIP LOCKED`; a transaction keeps the row lock through the bounded 5-second RPC and atomic status/history/outbox update. It reuses the durable order ID as Payment's idempotency key. An uncertain payment result retains reserved inventory and the original payment key. Recovery has durable attempt/age budgets and escalates exhausted uncertainty to manual review; it never releases that stock automatically. The test profile disables scheduled recovery so integration tests can invoke it deterministically; this stack uses dev/demo profiles and exercises the real scheduler.

The full-flow test awaits the asynchronous RESERVED → COMMITTED transition after confirmation and checks available/reserved/total quantities. Refunded cancellation returns committed stock to its original quantities. PAYMENT_PENDING must still hold RESERVED stock. Chaos recovery checks the same original reservation commits once within the shared recovery budget.

Email delivery uses `MockEmailService` in the dev profile. An `ORDER_CONFIRMED` notification row with `SENT` verifies Kafka consumption, authenticated order lookup, and invocation of the configured mock email service; it does not prove external email delivery. The test ties evidence to the newly created order ID.

`down` removes only this project's containers/network and retains data for diagnosis. No script command removes volumes. Orders and stock mutations persist between starts; stale notifications must not count as evidence for a new test. Before resetting any test-only volume, independently verify its exact `robomart-java25-e2e-resume_*` name and Compose labels. Preserve existing developer volumes.

The updated full-flow test and both chaos tests passed on 2026-10-24 (3 tests, zero failures/errors/skips), including automatic resumption of the same five pending orders. See [the recovery validation report](../../_bmad-output/test-artifacts/payment-pending-resume-validation-2026-10-24.md) for exact results, preserved data and limits.

The rebuilt Wave 7 stack passed all three tests on 2026-10-27 after correcting the extra provider backoff for breaker-denied calls. The 60-second recovery assertion is unchanged. Read-only probes verified all seven new orders cancelled, fully refunded and released, with one RESERVE/COMMIT/RELEASE movement each. Teardown preserved volumes and unrelated containers. See [Wave 7 validation](../../_bmad-output/test-artifacts/wave7-correctness-validation-2026-10-27.md) for final commands, results and limits.


Wave 8 uses the public Keycloak issuer http://localhost:18180/realms/robomart and the robomart-api access-token audience. Application containers fetch JWKs from keycloak:8180 without changing the issuer claim. stack.sh up runs configure-realm.py only against the label-verified isolated Keycloak and fixed loopback port, adding/updating the fixture clients' access-token audience mapper when retained realm data predates it. It never resets the realm or removes volumes; the current Saga addition provisions its own restricted identity only. Tokens/credentials are not printed. The Wave 8 execution below validates these changed targets separately from the earlier Wave 7 result.


Wave 8's rebuilt production image targets passed all3 E2E/chaos tests on2026-10-27, including the unchanged60second recovery budget. All7 JVMs mapped their image AOT cache. The retained test realm required one-time provisioning of the missing Inventory fixture client before the audience-only reconciler could run; existing identities/secrets were preserved, and subsequent mapper reconciliation reported0 updates. Orders22–28 were all cancelled, fully refunded and released, with one RESERVE/COMMIT/RELEASE each. The stack was shut down with every volume retained. See [Wave8 validation](../../_bmad-output/test-artifacts/wave8-platform-validation-2026-10-27.md).


Wave9 upgrades the retained Kafka/Schema Registry data to pinned Confluent8.2.4 images. Before apps start, configure-kafka.py verifies the fixed isolated labels/image/volume/cluster and enables only group.version1 after a dry-run; it never finalizes metadata or formats storage and first enablement requires stopped apps. E2E opts into KIP-848 consumer mode. Developer/production defaults remain classic until explicitly prepared. See [Kafka8 rollout](../../docs/kafka-8-upgrade.md) for the tested retained-data boundary and feature controls.


Wave9's rebuilt stack passed4 E2E/chaos tests on2026-10-27, adding real Gateway-admin image upload/public S3 read/deletion. All8 application groups were Consumer/Stable against retained Kafka data; feature/audience reconciliation made0 changes on repeat. Orders29–35 were cancelled, fully refunded and released, all inventory quantities returned to baseline, and existing123 image rows were preserved. All7 JVMs mapped AOT caches. Teardown retained every volume. See [Wave9 validation](../../_bmad-output/test-artifacts/wave9-data-infra-validation-2026-10-27.md).

Local validation (2026-11-09): [execution evidence](../../_bmad-output/test-artifacts/saga-service-extraction-2026-11-09.json) records 15 backend E2E tests, 7 browser tests, module coverage and fixture teardown. Hosted CI and production rollout are recorded separately.
