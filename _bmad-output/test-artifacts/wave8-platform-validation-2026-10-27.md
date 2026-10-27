# Wave 8 platform validation — 2026-10-27

Branch: `wave8/platform`, based on Wave 7 squash `7f9167b099e1c7167c61267d5a1584b2d7aa1d3b`.

This report is in progress. The full reactor and style gates have passed; image/E2E and PR checks remain. Detailed implementation decisions and intermediate failures are recorded in `docs/backend-remediation-waves-6-9.md`.

## Focused checks completed

All Maven commands used JDK25 and `backend/mvnw`. Background IDE compilation was suspended during Maven compilation/testing and resumed afterwards. Coverage gates remained enabled.

| Scope | Executed command | Result |
| --- | --- | --- |
| Style lifecycle | `./mvnw validate` | All 14 then-existing reactor modules passed Checkstyle12.3.1 |
| Order foundation | `./mvnw clean verify -pl :order-service -DskipE2ETests` | 180 unit +57 IT, coverage passed; before Framework retry migration |
| Inventory foundation | `./mvnw clean verify -pl :inventory-service -DskipE2ETests` | 75 unit +49 IT, coverage passed; real gRPC virtual-thread assertion |
| Product propagation | `./mvnw clean verify -pl :product-service -Dit.test=PlatformBaggageIT -DskipE2ETests` | 103 unit +2 real HTTP/Kafka IT and coverage passed |
| Notification | `./mvnw clean verify -pl :notification-service -DskipE2ETests` | 116 unit +22 IT, coverage passed; archive failure/retry, malformed bytes, replay |
| Payment | `./mvnw clean verify -pl :payment-service -DskipE2ETests` | 44 unit +34 IT, coverage passed; Framework concurrency control |
| Gateway | `./mvnw clean verify -pl :api-gateway -DskipITs -DskipE2ETests` | 62 tests and coverage passed; real JWT decoder, transport breaker, preserved upstream errors and timeout |
| Null-safety | `./mvnw -B -ntp clean verify -pl :common-lib,:security-lib -DskipITs -DskipE2ETests` | 44+17 tests and coverage passed; compiler negative probe rejected null contract violation, probe removed |
| Training isolation | `./mvnw -B -ntp clean verify -pl :build-support -DskipITs -DskipE2ETests` | 1 test and coverage passed; only explicit minimal framework configuration beans |
| Image-input packaging | `./mvnw -B -ntp clean install -DskipTests -DskipITs -DskipE2ETests` | All15 modules passed; tests intentionally skipped, not a test result |

The service-specific fixture changes eliminated forced-fork-exit warnings in the focused full Order/Inventory/Payment/Notification runs. Final reactor confirmation is pending.

## Images

`python3 backend/scripts/render-dockerfiles.py --check` passed for all9 generated Dockerfiles. Both source and prebuilt entry points retain their previous contexts/targets, including existing CD paths. Base-image multi-platform index digests were resolved with `docker buildx imagetools inspect` and are pinned in the renderer.

`docker build --progress=plain -f backend/Dockerfile.prebuilt --target product-service -t robo-w8-product-aot backend` passed. BuildKit created a 38,862,848-byte AOT cache with networking disabled. A second isolated container loaded that cache: `Opened AOT cache application.aot`, `Mapped static region`, `Using AOT-linked classes: true`. Compact object headers were enabled in training and reuse. This is core-context warming only; no full-service startup performance claim is made.

## Frontend and configuration

On the installed lockfile dependency graph, both apps passed:

- `npm run type-check --workspaces --if-present`
- `npm run test:unit --workspaces --if-present -- --run` — Customer240 +Admin87 tests
- `npm run build --workspaces --if-present`
- `npx prettier --check customer-website/src/ admin-dashboard/src/`

Compose core+app and isolated E2E `config --quiet` passed. Kustomize rendered base, production and ingress-tls overlays successfully. Shell syntax and renderer compilation passed. No cluster was changed. The user confirmed auto-deploy is currently disabled; deployment provisioning is separate from the authorized merge workflow.

## Still required

Final whole-reactor tests, explicit Checkstyle, all application images and remaining source-build paths, actual AOT reuse by application startup, isolated persisted-realm reconciliation, real E2E/chaos, PR CI and squash merge. Proto buf lint/breaking against main passed; the initial reactor Avro compatibility suite passed3 cases.

The initial whole-reactor run failed only the Order404 Pact media-type expectation before reaching later services. Both pact declaration and committed provider contract were corrected to application/problem+json. Two focused Notification consumer tests passed using the actual typed OrderServiceClient. A fresh whole-reactor verification follows that correction.


## Final reactor and style gates

After the Pact correction, ./mvnw -B -ntp clean verify -DskipE2ETests passed all15 modules in6m20s. Maven execution summaries and individual XML testcase elements agree: **697 unit +286 integration tests =983**, zero failures/errors/skips. Three JUnit nested-class XML suite headers undercount their testcase elements, so counts use actual testcase elements and Maven execution summaries rather than summing only root attributes. Every configured coverage gate passed, including security/configuration classes. There was no forced-fork-exit warning.

| Module | Unit | Integration |
| --- | ---: | ---: |
| Build Support | 1 | 0 |
| Common | 44 | 0 |
| Security | 17 | 0 |
| Proto | 8 | 0 |
| Events | 2 | 3 |
| Product | 103 | 81 |
| Cart | 45 | 40 |
| Gateway | 62 | 0 |
| Order | 180 | 57 |
| Inventory | 75 | 49 |
| Payment | 44 | 34 |
| Notification | 116 | 22 |

./mvnw -B -ntp checkstyle:check then passed all15 modules. Existing advisory warnings remain at their existing severity; no checker/coverage gate was disabled. Proto buf lint and wire-breaking comparison against main passed. Events' real Kafka/Schema Registry Avro compatibility suite passed3 tests in the final reactor.
