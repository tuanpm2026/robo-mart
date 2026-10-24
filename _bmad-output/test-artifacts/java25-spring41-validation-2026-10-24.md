# Java 25 / Spring Boot 4.1 Validation

Date: 2026-10-24 (Asia/Ho_Chi_Minh).

This report records the earlier dependency-upgrade verification snapshot. E2E/chaos were subsequently corrected and executed successfully; see the [follow-up isolated validation report](java25-e2e-chaos-validation-2026-10-24.md) for those results and later backend regression checks.

## Result

The final clean reactor build and full default backend verification passed. Local runtime: Eclipse Temurin 25.0.4.1, Maven Wrapper 3.9.11, Docker Desktop engine 29.5.2. Spring Boot 4.1.2 and Spring Cloud 2025.1.4 are configured in the parent POM.

Executed **591 unit/contract tests and 139 integration/provider-contract tests (730 total)** with **0 failures, 0 errors, 0 skipped** among the executed suites. The E2E/chaos module was explicitly disabled and is not included in these counts. All configured coverage gates passed at the existing 80% line threshold.

| Module | Surefire tests | Failsafe tests | JaCoCo line coverage |
| --- | ---: | ---: | ---: |
| common-lib | 47 | 0 | 84.30% |
| security-lib | 15 | 0 | 90.32% |
| proto | 8 | 0 | N/A (generated/configuration only) |
| events | 2 | 3 | N/A (generated/configuration only) |
| test-support | 0 | 0 | N/A (generated/configuration only) |
| product-service | 85 | 57 | 87.03% |
| cart-service | 58 | 21 | 91.34% |
| api-gateway | 45 | 0 | 100.00% |
| order-service | 123 | 17 | 86.45% |
| inventory-service | 79 | 21 | 83.53% |
| payment-service | 42 | 13 | 82.56% |
| notification-service | 87 | 7 | 91.65% |

Counts use individual `<testcase>` entries in `backend/*/target/{surefire,failsafe}-reports/TEST-*.xml`, cross-checked against Maven result totals. This corrects an earlier count of 722 caused by undercounted `testsuite.tests` attributes in nested JUnit reports; coverage comes from the final `backend/*/target/site/jacoco/jacoco.xml`. Existing coverage exclusions were preserved. Test-support has no independent test suite; generated-only modules have no measured production lines.

## Commands and outcomes

Executed from `backend/`:

```bash
./mvnw clean install -T 1C -DskipTests -DskipITs -DskipE2ETests --no-transfer-progress
./mvnw verify -fae -DskipE2ETests -DskipBufLint=false --no-transfer-progress
./mvnw checkstyle:check --no-transfer-progress
```

All three commands returned success. The full verification completed in 8 minutes 3 seconds and all 14 reactor modules reported success. The first command compiles production and test sources but does not execute tests; the second command is the unit/integration/schema/coverage evidence. Java compilation used `release 25`.

Docker's credential helper initially stalled. Public image pulls and Testcontainers runs used a temporary empty `DOCKER_CONFIG` outside the repository; no user registry credentials or Docker settings were changed.

Additional successful checks:

- `buf lint`, both directly and through the final proto verification.
- Effective POM and Order dependency tree: gRPC/Protobuf follow Boot management; the actual Resilience4j runtime chain is 2.4.0.
- Compose `config --quiet` for core/app, the full overlay, and the Pact overlay.
- `git diff --check`.
- Build `backend/Dockerfile.prebuilt` target `order-service` as `robomart-upgrade/order-service:java25`; run its `java -version` and confirm Temurin 25.0.4.1.

CI YAML and all backend Dockerfiles were updated, but no remote GitHub Actions run or deployment was triggered. The other application image targets were not independently built. Frontend dependencies were unchanged.

## Regression coverage and resolved failures

The initial integration run exposed a legacy non-Jakarta Swagger annotations JAR shadowing springdoc's Jakarta annotations. A narrow Confluent dependency exclusion resolves it. The authenticated OpenAPI test now validates product paths/response schema; the anonymous request remains unauthorized.

Avro 1.12.2 rejected generated Robo-Mart event classes. An events-module auto-configuration registers only its 15 exact generated record names while preserving existing trusted types. Tests verify actual Confluent serializer/deserializer round-trip, rejection of unlisted classes, and idempotent registration. Product outbox/Kafka/indexing integration tests passed after the fix.

Spring AOP regression tests verify business errors bypass circuit-breaker failure counting and transient gRPC errors invoke fallback/open the breaker. Existing deferred RETRY-NOOP semantics were preserved rather than changed during the dependency migration.

## Unexecuted checks and deployment prerequisites

E2E and chaos tests were inspected but not executed: their create-order requests omit required fields, the full-flow test expects a nonexistent notification endpoint, and the service-kill test assumes asynchronous checkout although checkout is synchronous. See `docs/java25-spring41-upgrade.md` for details and the separate disposable-stack requirements. These are source-inspection findings, not reported runtime failures from executed E2E tests. Performance tests were not executed; no performance improvement is claimed.

Existing Elasticsearch 8.17 data was not migrated or opened with the new 9.4.8 image. Preserve snapshots and follow the documented 8.19 intermediate upgrade or a separately tested reindex/cutover plan. No existing user volumes were removed or modified by this task.
