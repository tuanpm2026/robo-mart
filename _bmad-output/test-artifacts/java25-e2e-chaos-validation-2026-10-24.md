# Isolated E2E and chaos validation

Date: 2026-10-24 (Asia/Ho_Chi_Minh). Completed: E2E/chaos and full backend verification passed.

This is the earlier validation snapshot, before automatic pending-payment recovery. That recovery is now implemented and tested in a fresh project; see [the follow-up report](payment-pending-resume-validation-2026-10-24.md). Existing volumes from this earlier run were preserved.

## Scope and isolation

Java 25 / Spring Boot 4.1.2 / Spring Cloud 2025.1.4, using the dedicated Compose project `robomart-java25-e2e`. The stack uses alternate loopback ports, dedicated image tags, service-owned databases in one isolated PostgreSQL instance, and fresh project-scoped data volumes. No default application stack was started. Frontend, external email delivery and k6 performance testing are outside these HTTP suites.

Before provisioning, an inventory of 23 existing stopped containers and 54 existing volumes was saved outside the repository. Existing Elasticsearch 8.x data was not opened by Elasticsearch 9.4.8. The isolated stack has its own search volume. After E2E teardown and again after full reactor verification, all 23 original container IDs/states and all 54 original volume names were preserved. No isolated E2E containers remained; test volumes were retained.

## Implementation changes

- Align E2E requests and assertions with synchronous checkout, catalog arrays, cart merge, cancellation, stock reservation/refund and actual notification persistence.
- Obtain real customer/admin JWTs from the isolated Keycloak realm. Read-only SQL probes select containers by exact Compose labels and enforce server-side read-only transactions.
- Stop Payment before checkout, assert `PAYMENT_PENDING` with reserved stock and no payment, then verify a fresh checkout succeeds and the breaker closes within a shared 60-second recovery budget. Existing pending orders intentionally remain observable; automatic resume is deferred in the implementation.
- Measure a real 500 ms Chaos Monkey service-layer assault and its metric. This does not simulate network latency. Restore faults in teardown.
- Add the Boot 4 RestClient starter required by Chaos Monkey's optional REST instrumentation. Expose the isolated chaos endpoint through the Boot 4 access property only; legacy endpoint `enabled` and `access` cannot coexist.
- Enable Product and Inventory's `demo` profile only in the dedicated stack for catalog/index/inventory fixtures.
- Authenticate Notification background HTTP calls through OAuth2 client credentials. Order permits `NOTIFICATION_SERVICE` only to GET one numeric order detail; other customer/admin operations remain denied. Non-404 downstream errors now propagate to the notification retry/DLQ path. Auth is opt-in and requires the documented client registration in other deployments.

`notification_log.status=SENT` means the configured `MockEmailService` completed. It is not evidence of external SMTP delivery. Product/Inventory demo seed migrations are fixtures for this dedicated stack and must not be pointed at existing application databases.

## Runtime findings

Initial attempts exposed missing demo inventory, mutually exclusive actuator properties, a missing Boot module for active Chaos Monkey, and a real Hibernate orphan-removal failure during HTTP checkout. These failed attempts are diagnostic evidence, not passing runs. Final commands and results will be recorded after fixes.

## Executed E2E and chaos

From the repository root, after packaging current service JARs:

```bash
infra/e2e/stack.sh build
infra/e2e/stack.sh up
infra/e2e/stack.sh test
infra/e2e/stack.sh down
```

The final E2E command internally ran `./mvnw verify -pl :e2e-tests -DskipE2ETests=false -De2e.enabled=true -Dchaos.enabled=true` from `backend/`. Result: **3 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**, completed at 12:12:59 ICT in 46.168 seconds.

| Suite | Tests | Duration | Result |
| --- | ---: | ---: | --- |
| FullOrderFlowE2EIT | 1 | 4.437 s | Passed |
| ServiceKillChaosIT | 2 | 40.60 s | Passed |

Assertions covered actual HTTP requests, real gRPC downstreams, PostgreSQL/Redis/Elasticsearch/Kafka/Avro persistence and delivery, and Keycloak tokens. The notification query matched the newly created order, not a preexisting record. Recovery assertions included exact `PAYMENT_PENDING` states, no charges during outage, and fresh checkout completion with the breaker CLOSED within the shared 60-second budget. Five pending orders remain in this disposable database by design because the implementation has no automatic resume/public cancellation path for that state.

Before teardown, Chaos Monkey reported `enabled=false`, all five assault flags were false, and Payment was healthy. Teardown removed only this project's containers and network, retaining test data volumes. Raw Maven output and concise suite reports are preserved in [java25-e2e-chaos-2026-10-24](java25-e2e-chaos-2026-10-24/).

The HTTP checkout failure was fixed by preserving Hibernate's orphan-removal collection and initializing created items with the owning order in the creation transaction. Order lookup fetches items for detached use. A regression integration test exercises real HTTP create/cancel with OSIV, real database/Saga and mocked gRPC peers; it passed in the final reactor verification below.

## Final backend verification

Executed from `backend/`, with the isolated stack stopped to avoid memory contention:

```bash
./mvnw verify -fae -DskipE2ETests -DskipBufLint=false --no-transfer-progress
./mvnw checkstyle:check --no-transfer-progress
```

Both commands returned success. Full verification completed at **12:21:30 ICT**, in **8 minutes 1 second**, with all 14 reactor modules successful. It executed **594 unit/consumer-contract tests and 141 integration/provider-contract tests (735 total)**, with **0 failures, 0 errors and 0 skipped** among those suites. E2E is intentionally disabled in this command because all three E2E/chaos tests were executed separately against the dedicated stack. Combined evidence: **738 tests passed**.

Counts use individual XML `<testcase>` entries and agree with the Maven result summaries. Some nested JUnit suites expose undercounted `testsuite.tests` attributes; the earlier upgrade report has been corrected using its retained Maven output (730 tests in that earlier snapshot).

| Module | Unit/consumer contract | Integration/provider contract | JaCoCo line coverage |
| --- | ---: | ---: | ---: |
| common-lib | 47 | 0 | 84.30% |
| security-lib | 15 | 0 | 90.32% |
| proto | 8 | 0 | N/A |
| events | 2 | 3 | N/A |
| test-support | 0 | 0 | N/A |
| product-service | 85 | 57 | 87.03% |
| cart-service | 58 | 21 | 91.34% |
| api-gateway | 45 | 0 | 100.00% |
| order-service | 123 | 19 | 86.97% |
| inventory-service | 79 | 21 | 83.53% |
| payment-service | 42 | 13 | 82.56% |
| notification-service | 90 | 7 | 91.65% |

All configured JaCoCo gates passed at the existing 80% threshold; exclusions were unchanged. Generated/configuration-only modules have no measured production coverage. Schema lint ran through the proto module. Checkstyle, isolated Compose configuration validation, shell syntax, Python compilation and `git diff --check` passed.

The HTTP OrderSaga regression passed (five tests in that suite), along with the restricted Notification role checks and three OAuth client configuration tests. No remote GitHub Actions run, deployment, Elasticsearch data migration, frontend suite, k6 performance test or external email delivery test was executed. Frontend files were unchanged.

The public Docker credential-helper workaround used an empty temporary `DOCKER_CONFIG` outside the repository. Existing Docker credential settings and local `.env` files were not changed. Existing user containers were not started/stopped/recreated, and no volume-removal command was used.
