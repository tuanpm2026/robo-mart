# Java 25 and Spring Boot 4.1 Upgrade

Date: 2026-10-24. Status: implementation complete; build, schema lint, Checkstyle and all configured coverage gates passed. Latest verification, including automatic pending-payment recovery, executed 775 backend unit/integration/contract tests plus all three isolated E2E/chaos tests (778 passed, zero failures/errors/skips). See the [latest validation report](../_bmad-output/test-artifacts/payment-pending-resume-validation-2026-10-24.md) for commands, counts and limits, and the [initial upgrade snapshot](../_bmad-output/test-artifacts/java25-spring41-validation-2026-10-24.md) for earlier dependency validation.

## Intended versions and dependency policy

| Component | Target |
| --- | --- |
| Java / CI / Docker runtime | Temurin 25 LTS; Maven Enforcer requires `[25,26)` |
| Spring Boot / Spring Cloud | 4.1.2 / 2025.1.4 |
| Spring gRPC / gRPC / Protobuf (Boot-managed) | 1.1.2 / 1.84.1 / 4.36.2 |
| Pact / Keycloak Testcontainers adapter | 4.7.5 / 4.4.0 |
| Guava / Error Prone annotations | 33.7.2-jre / 2.51.0 |
| springdoc / Resilience4j | 3.1.1 / 2.4.0 (`resilience4j-spring-boot4`) |
| Avro runtime and generator | 1.12.2 |
| Logstash Logback Encoder / ShedLock | 9.0 / 7.10.1 |
| JaCoCo / ArchUnit | 0.8.15 / 1.5.1 |
| Confluent serializer, Kafka and Schema Registry images | 7.9.10 |
| Elasticsearch local and integration-test image | 9.4.8 |
| MapStruct | 1.6.3 |

Use the Spring Boot parent and Spring Cloud BOM for managed dependencies; avoid independent pins for Spring Framework, Security, Data, Hibernate, Jackson, Kafka clients, Flyway, Testcontainers, gRPC, and Protobuf. The Protobuf Maven plugin now uses Boot's `protobuf-java.version` and `grpc-java.version` for code generation so generated sources match runtime dependencies. The Resilience4j Boot 4 starter has an explicit managed version because its 2.4.0 BOM does not include that artifact. The Resilience4j BOM must precede the Cloud BOM because Maven gives the first imported BOM priority. Guava and Error Prone annotations retain convergence pins in the parent POM. The legacy Xolstice plugin version uses a distinct property to avoid overriding Boot’s Ascopes Protobuf plugin version.

Treat the effective POM and dependency tree as the resolved-version evidence. Keep generated Java code out of manual contract edits. Frontend dependency updates and unrelated infrastructure major upgrades are outside this change.

## Runtime compatibility fixes

HTTP checkout exposed a Hibernate orphan-removal failure when `Order.items` was replaced while OSIV kept the order managed. The order now preserves its managed collection, initializes created children in the creation transaction, and fetches items on individual lookups for detached callers. A real HTTP checkout/cancellation regression exercises this persistence boundary.

Confluent 7.9.10 brings the legacy `swagger-annotations` artifact, which defines the same classes as springdoc's Jakarta annotation artifact. The serializer dependency excludes the legacy artifact to prevent an OpenAPI `NoSuchMethodError`; Springdoc supplies the compatible Jakarta classes.

Avro 1.12.2 validates classes loaded from schemas. The events module auto-registers an exact allowlist of its 15 generated named records before producer/consumer beans initialize. Existing trusted types remain allowed; arbitrary classes and other classes in the events namespace are not added. When adding a named event record, update this allowlist and its serialization regression coverage. The module retains its schema-only use outside Boot; non-Boot consumers must configure Avro trust explicitly.

## Prerequisites and validation

Install Temurin JDK 25, Docker with Compose, and `buf` for schema checks. On macOS, select the JDK before running Maven:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
cd backend
./mvnw -version
```

Run from `backend/`, using the committed wrapper:

```bash
./mvnw clean install -T 1C -DskipTests -DskipITs -DskipE2ETests
./mvnw checkstyle:check
./mvnw verify -pl :common-lib,:security-lib,:proto,:api-gateway -DskipITs -DskipE2ETests
# Full unit + integration verification, including the configured coverage gates:
./mvnw verify -DskipE2ETests
# Optional schema lint; requires buf:
./mvnw verify -pl :proto -DskipBufLint=false -DskipITs -DskipE2ETests
```

For isolated service verification after dependency installation, use `./mvnw verify -pl :product-service -DskipE2ETests` and repeat for cart, order, inventory, payment, notification, and events. Docker must be available for Testcontainers. Keep the inherited 80% line coverage gate enabled. `-DskipTests` also skips Failsafe integration tests and must never be used as evidence that integration tests passed.

From the repository root, validate Compose without starting or replacing containers:

```bash
docker compose -f infra/docker/docker-compose.yml --profile core --profile app config --quiet
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.full.yml --profile core --profile app --profile full config --quiet
```

End-to-end and chaos tests use the dedicated stack described in [infra/e2e/README.md](../infra/e2e/README.md). From the repository root, package the current backend, then run:

```bash
infra/e2e/stack.sh build
infra/e2e/stack.sh up
infra/e2e/stack.sh test
infra/e2e/stack.sh down
```

The script obtains JWTs into the process environment and explicitly enables both suites. Do not put tokens in Maven command arguments. Compose labels guard database probes and Payment stop/start against the exact isolated project. `down` preserves volumes. Existing developer containers and volumes must remain untouched.

## E2E and chaos behavior

The corrected suites cover catalog search, anonymous cart merge, customer RBAC, synchronous confirmed checkout, exact payment and stock persistence, asynchronous notification persistence, cancellation, refund and stock release. A Payment outage begins before checkout and produces `PAYMENT_PENDING`; recovery is measured by all five original orders becoming confirmed and the circuit breaker closing within 60 seconds, including restart time. No fresh checkout triggers that recovery. The scheduled recovery worker now resumes those same pending orders automatically, retaining the reservation and original payment key. Acknowledged payment results, confirmation history, audit and outbox commit atomically under a PostgreSQL row lock. See [automatic recovery validation](../_bmad-output/test-artifacts/payment-pending-resume-validation-2026-10-24.md) for the latest results.

The latency scenario verifies an actual 500 ms Chaos Monkey service-layer assault and an increasing assault metric. It does not inject network latency. Notification `SENT` records use the configured mock email provider. See the [isolated validation report](../_bmad-output/test-artifacts/java25-e2e-chaos-validation-2026-10-24.md) for executed results and remaining limits.

Notification background calls support OAuth2 client credentials via `notification.service-auth.enabled=true` and a `notification` client registration. The isolated realm supplies `NOTIFICATION_SERVICE`, restricted to GET one numeric order detail. Other deployments must provision their own confidential client, credentials and token endpoint; the test fixtures are not deployment credentials. Non-404 HTTP failures propagate through retry/DLQ rather than being treated as a missing order.

Boot 4 modularization requires `spring-boot-starter-restclient` when using Chaos Monkey's RestTemplate instrumentation; the four services that include Chaos Monkey now declare it. Only the isolated Inventory container activates its profile and exposes the endpoint. Boot 4 endpoint `access` and legacy `enabled` properties are mutually exclusive.

## gRPC configuration migration

Boot 4.1 uses native gRPC starters. Client channels move from `spring.grpc.client.<name>.address` to `spring.grpc.client.channel.<name>.target`; plaintext configuration becomes `ssl.enabled: false`. Existing development endpoints remain `static://localhost:9094` and `static://localhost:9095`.

The application preserves `GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS` and `GRPC_CLIENT_PAYMENT_SERVICE_ADDRESS` as environment placeholders for channel targets. Compose now supplies these names. Custom deployments that used `SPRING_GRPC_CLIENT_*_ADDRESS` must migrate to the preserved placeholders or the new native target properties. Server ports remain under `spring.grpc.server.port`. Validate actual downstream calls after changing configuration.

## Elasticsearch data migration

The new Compose tag is for an Elasticsearch 9.4.8-compatible environment. **Do not start it against an existing 8.17 data volume.** First snapshot the existing cluster and prove the snapshot can be restored; preserve application database backups and record index mappings, aliases, templates, and ingest configuration.

For existing 8.x data, upgrade to the latest available **8.19.x** first, run the matching Kibana Upgrade Assistant there, and resolve critical deprecations and incompatible indices. Indices created before 8.0 may need reindexing or another explicitly supported action. Only then migrate to 9.4.8, checking Elastic's version chronology and breaking changes for the exact patch pair. Compose does not provision Kibana or automate these steps. See Elastic's [preparation guide](https://www.elastic.co/docs/deploy-manage/upgrade/prepare-to-upgrade), [Upgrade Assistant guide](https://www.elastic.co/docs/deploy-manage/upgrade/prepare-to-upgrade/upgrade-assistant), and [self-managed upgrade procedure](https://www.elastic.co/docs/deploy-manage/upgrade/deployment-or-cluster/elasticsearch).

An alternative is a separately provisioned 9.4.8 cluster with a deliberately tested reindex/rebuild and cutover plan. Do not assume the application automatically reconstructs the entire existing search index. Do not delete the original volume or run `docker compose down -v` as an upgrade step.

## Rollback limits

Retain previous application image digests and configuration. Application rollback requires matching Java-compatible artifacts and compatible database schemas, event contracts, and downstream services. Reverting an image does not undo Flyway migrations, Kafka events, payments, refunds, or other side effects.

Elasticsearch cannot downgrade an upgraded data directory. Recover the earlier version in a separate compatible cluster from a tested pre-upgrade snapshot; account for writes made after that snapshot. A rollback plan must include data reconciliation and a controlled cutover, not just an image-tag change. See Elastic's [upgrade procedure and rollback restrictions](https://www.elastic.co/docs/deploy-manage/upgrade/deployment-or-cluster/elasticsearch).
