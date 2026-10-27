# Isolated Java 25 E2E stack

This standalone Compose stack uses the project `robomart-java25-e2e-resume`, project-scoped volumes, no fixed container names, and loopback-only alternate ports. It never references developer Compose volumes. Do not merge it with the default Compose file or reuse its volumes for production.

Prerequisites: JDK 25, Python 3, Docker Compose, approximately 8 GiB allocated to Docker, and packaged backend JARs. Finish integration tests before starting this stack so Testcontainers do not compete for memory. Service-owned PostgreSQL databases share one dedicated PostgreSQL container. Memory caps total approximately 7.3 GiB; application JVMs use 192 MiB maximum heaps and 256 MiB maximum metaspace. Startup is sequential. Frontend and monitoring services are not required by these HTTP tests.

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

`build` generates an ignored `.runtime/robomart-realm.json` from the committed main realm, replacing only Inventory/Notification secrets with isolated public test fixtures. The main import already contains both service clients/roles. These fixture secrets and all database/admin credentials here are unsuitable for deployment. The generator leaves the source realm unchanged. Application images use the production `backend/Dockerfile.prebuilt` targets, including layered extraction, pinned JRE, compact headers and side-effect-free AOT training. They install `curl` explicitly for healthchecks. The build first checks generated Dockerfiles for drift.

Notification background authentication is enabled by default and explicit in this stack. Its cached client-credentials token permits only the required GET order detail, reconciliation summaries and source audit logs; it grants no general admin/customer writes. Inventory uses its separate `INVENTORY_SERVICE` client to read the minimal internal order-status endpoint for safe reservation expiry. Existing live realms need separately provisioned matching roles, confidential clients and generated secrets; startup realm import does not update them.

Product uses the checked `products_v1` index and `products-search` alias, with checkpointed PostgreSQL bootstrap and authoritative versioned writes. Existing concrete `products` indexes are preserved. The stack keeps uploaded images in its own named `product-images` volume and returns Gateway URLs. Product DLTs are application-provisioned with the single-broker replication factor. Reindex maintenance is paged: POST `/api/v1/admin/products/reindex?afterId=0&size=100` as ADMIN, then repeat using `nextAfterId` until `hasNext=false`; failed pages are safe to retry.

Host endpoints: gateway `127.0.0.1:18080`, Keycloak `localhost:18180`, order `127.0.0.1:18083`, inventory `127.0.0.1:18084`, payment `127.0.0.1:18086`. The Keycloak issuer is `http://localhost:18180/realms/robomart`; internal JWK/token requests use container DNS. Downstream HTTP/gRPC requests use service DNS.

`test` obtains the committed demo-admin JWT and exports `E2E_ADMIN_TOKEN` and `CHAOS_ADMIN_TOKEN` in its subprocess environment. It never prints JWTs or passes them as Maven arguments. It also exports `E2E_BASE_URL`, `E2E_KEYCLOAK_URL`, `E2E_ORDER_URL`, `E2E_INVENTORY_URL`, `E2E_PAYMENT_URL`, `E2E_COMPOSE_PROJECT`, and `E2E_PAYMENT_SERVICE`, and explicitly enables E2E/chaos. Customer and product/inventory fixtures come from the committed realm and Flyway seeds enabled by the `demo` profile for Product and Inventory.

Java E2E probes and chaos commands resolve Docker from `/usr/bin/docker`, `/usr/local/bin/docker`, or `/opt/homebrew/bin/docker`, following symlinks to the executable. They never search `PATH`. For a different installation, export `E2E_DOCKER_BINARY` with an absolute path to a regular executable Docker file before `stack.sh test`; a relative, missing, or non-executable override fails immediately. The executable override does not change Compose project/service ownership checks.

Chaos Monkey starts disabled. Its assault adds latency inside the Inventory service through Spring instrumentation; it does not emulate network or gRPC transport latency. Its watcher/actuator are available only in this isolated stack so tests can enable a measured assault and disable it afterward. Chaos tests must validate container ownership using both Compose project and service labels before stopping Payment. `stack.sh payment-stop` and `payment-start` implement the same ownership guard for diagnosis.

The payment outage test stops Payment before creating five orders and verifies that the scheduled worker automatically confirms those same orders after Payment restarts, without a fresh checkout to trigger recovery. The shared 60-second recovery budget includes startup and breaker recovery. Each original order must have exactly one completed payment, one completed provider charge operation, one finalized payment intent, one inventory reservation movement and one committed stock movement, one successful audit for each forward step, and one confirmation event/history entry. The test registers resumed orders for cancellation, refund, and stock-release cleanup as soon as they are observed.

The worker is enabled by default through `saga.payment-recovery.enabled`. Its defaults are `check-interval-ms: 5000`, `initial-delay-ms: 5000`, `retry-delay: 30s`, and `batch-size: 20`. The initial hold and each provider failure defer the next attempt by 30 seconds. A breaker-denied call sends no RPC and rechecks within five seconds (or the configured shorter retry delay). Each scan claims due pending orders with `FOR NO KEY UPDATE SKIP LOCKED`; a transaction keeps the row lock through the bounded 5-second RPC and atomic status/history/outbox update. It reuses the durable order ID as Payment's idempotency key. An uncertain payment result retains reserved inventory and the original payment key. Recovery has durable attempt/age budgets and escalates exhausted uncertainty to manual review; it never releases that stock automatically. The test profile disables scheduled recovery so integration tests can invoke it deterministically; this stack uses dev/demo profiles and exercises the real scheduler.

The full-flow test awaits the asynchronous RESERVED → COMMITTED transition after confirmation and checks available/reserved/total quantities. Refunded cancellation returns committed stock to its original quantities. PAYMENT_PENDING must still hold RESERVED stock. Chaos recovery checks the same original reservation commits once within the shared recovery budget.

Email delivery uses `MockEmailService` in the dev profile. An `ORDER_CONFIRMED` notification row with `SENT` verifies Kafka consumption, authenticated order lookup, and invocation of the configured mock email service; it does not prove external email delivery. The test ties evidence to the newly created order ID.

`down` removes only this project's containers/network and retains data for diagnosis. No script command removes volumes. Orders and stock mutations persist between starts; stale notifications must not count as evidence for a new test. Before resetting any test-only volume, independently verify its exact `robomart-java25-e2e-resume_*` name and Compose labels. Preserve existing developer volumes.

The updated full-flow test and both chaos tests passed on 2026-10-24 (3 tests, zero failures/errors/skips), including automatic resumption of the same five pending orders. See [the recovery validation report](../../_bmad-output/test-artifacts/payment-pending-resume-validation-2026-10-24.md) for exact results, preserved data and limits.

The rebuilt Wave 7 stack passed all three tests on 2026-10-27 after correcting the extra provider backoff for breaker-denied calls. The 60-second recovery assertion is unchanged. Read-only probes verified all seven new orders cancelled, fully refunded and released, with one RESERVE/COMMIT/RELEASE movement each. Teardown preserved volumes and unrelated containers. See [Wave 7 validation](../../_bmad-output/test-artifacts/wave7-correctness-validation-2026-10-27.md) for final commands, results and limits.


Wave 8 uses the public Keycloak issuer http://localhost:18180/realms/robomart and the robomart-api access-token audience. Application containers fetch JWKs from keycloak:8180 without changing the issuer claim. stack.sh up runs configure-realm.py only against the label-verified isolated Keycloak and fixed loopback port, adding/updating the five fixture clients' access-token audience mapper when retained realm data predates it. It never resets the realm, alters roles/secrets, or removes volumes. Tokens/credentials are not printed. The final Wave 8 E2E result will be recorded after execution; the Wave 7 result above does not validate the changed Wave 8 stack.
