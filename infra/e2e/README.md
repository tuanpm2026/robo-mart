# Isolated Java 25 E2E stack

This standalone Compose stack uses the project `robomart-java25-e2e-resume`, fresh project-scoped volumes, no fixed container names, and loopback-only alternate ports. It never references developer Compose volumes. Do not merge it with the default Compose file or reuse its volumes for production.

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

`build` generates an ignored `.runtime/robomart-realm.json` from the committed realm. It adds a confidential `notification-service` client and a service account with `NOTIFICATION_SERVICE`, used only to fetch order details for notification delivery. Its fixed secret and all database/admin credentials here are public test fixtures, unsuitable for deployment. The source realm remains unchanged. Fixtures are copied into images; no developer data directories are mounted. Application images install `curl` explicitly for healthchecks.

Notification authentication is opt-in through `notification.service-auth.enabled` (`NOTIFICATION_SERVICE_AUTH_ENABLED` in this stack). The `notification` OAuth2 registration uses `client_credentials`, client ID `notification-service`, and the internal Keycloak token URL. Order authorizes `NOTIFICATION_SERVICE` only for `GET /api/v1/admin/orders/{numericOrderId}`; it grants no general admin or customer access. Other deployments must provision this role/client and configure a secret through their own secret-management mechanism before enabling it. The existing developer realm is not changed by the E2E fixture generator.

Host endpoints: gateway `127.0.0.1:18080`, Keycloak `localhost:18180`, order `127.0.0.1:18083`, inventory `127.0.0.1:18084`, payment `127.0.0.1:18086`. The Keycloak issuer is `http://localhost:18180/realms/robomart`; internal JWK/token requests use container DNS. Downstream HTTP/gRPC requests use service DNS.

`test` obtains the committed demo-admin JWT and exports `E2E_ADMIN_TOKEN` and `CHAOS_ADMIN_TOKEN` in its subprocess environment. It never prints JWTs or passes them as Maven arguments. It also exports `E2E_BASE_URL`, `E2E_KEYCLOAK_URL`, `E2E_ORDER_URL`, `E2E_INVENTORY_URL`, `E2E_PAYMENT_URL`, `E2E_COMPOSE_PROJECT`, and `E2E_PAYMENT_SERVICE`, and explicitly enables E2E/chaos. Customer and product/inventory fixtures come from the committed realm and Flyway seeds enabled by the `demo` profile for Product and Inventory.

Chaos Monkey starts disabled. Its assault adds latency inside the Inventory service through Spring instrumentation; it does not emulate network or gRPC transport latency. Its watcher/actuator are available only in this isolated stack so tests can enable a measured assault and disable it afterward. Chaos tests must validate container ownership using both Compose project and service labels before stopping Payment. `stack.sh payment-stop` and `payment-start` implement the same ownership guard for diagnosis.

The payment outage test stops Payment before creating five orders and verifies that the scheduled worker automatically confirms those same orders after Payment restarts, without a fresh checkout to trigger recovery. The shared 60-second recovery budget includes startup and breaker recovery. Each original order must have exactly one completed payment, one completed provider charge operation, one payment outbox event, one inventory reservation movement, one successful audit for each forward step, and one confirmation event/history entry. The test registers resumed orders for cancellation, refund, and stock-release cleanup as soon as they are observed.

The worker is enabled by default through `saga.payment-recovery.enabled`. Its defaults are `check-interval-ms: 5000`, `initial-delay-ms: 5000`, `retry-delay: 30s`, and `batch-size: 20`. The initial hold and each transient failure defer the next attempt by 30 seconds. Each scan claims due pending orders with `FOR UPDATE SKIP LOCKED`; a transaction keeps the row lock through the bounded 5-second RPC and atomic status/history/outbox update. It reuses the durable order ID as Payment's idempotency key. An uncertain payment result stays retryable; it must not release the reserved inventory or allocate a new payment key. The test profile disables scheduled recovery so integration tests can invoke it deterministically; this stack uses dev/demo profiles and exercises the real scheduler.

Email delivery uses `MockEmailService` in the dev profile. An `ORDER_CONFIRMED` notification row with `SENT` verifies Kafka consumption, authenticated order lookup, and invocation of the configured mock email service; it does not prove external email delivery. The test ties evidence to the newly created order ID.

`down` removes only this project's containers/network and retains data for diagnosis. No script command removes volumes. Orders and stock mutations persist between starts; stale notifications must not count as evidence for a new test. Before resetting any test-only volume, independently verify its exact `robomart-java25-e2e-resume_*` name and Compose labels. Preserve existing developer volumes.

The updated full-flow test and both chaos tests passed on 2026-10-24 (3 tests, zero failures/errors/skips), including automatic resumption of the same five pending orders. See [the recovery validation report](../../_bmad-output/test-artifacts/payment-pending-resume-validation-2026-10-24.md) for exact results, preserved data and limits.
