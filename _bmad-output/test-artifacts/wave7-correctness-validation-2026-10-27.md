# Wave 7 correctness validation

Date: 2026-10-27 (Asia/Ho_Chi_Minh). Branch: wave7/correctness, PR #45. Implementation head: 75b83d2df750aac7dc136602cad018ee0685f1ba.

## Backend

Executed from backend/ using Temurin JDK 25, with VS Code's background Java compiler temporarily suspended during Maven execution and resumed afterward:

```bash
./mvnw clean verify -DskipE2ETests
./mvnw checkstyle:check
```

Both builds succeeded. Maven execution summaries report 691 unit tests and 283 integration tests (974 total), zero failures/errors/skips in executed suites. E2E was deliberately excluded. Configured coverage gates remained enabled and passed.

| Module | Unit | Integration | Line coverage |
| --- | ---: | ---: | ---: |
| common-lib | 48 | 0 | 84.57% |
| security-lib | 15 | 0 | 90.32% |
| proto | 8 | 0 | N/A |
| events | 2 | 3 | N/A |
| product-service | 103 | 79 | 93.02% |
| cart-service | 45 | 40 | 96.47% |
| api-gateway | 57 | 0 | 100.00% |
| order-service | 180 | 57 | 95.05% |
| inventory-service | 75 | 49 | 94.21% |
| payment-service | 42 | 34 | 90.25% |
| notification-service | 116 | 21 | 93.89% |

The Order service additionally passed its focused full-module verify before the final reactor (180 unit + 57 IT). Its new PostgreSQL regression verifies that an OPEN breaker sends no charge/release RPC, schedules a prompt recheck and recovers with the original payment key after closing.

Order, Inventory and Payment still emit Surefire's forced fork-exit warning after successful assertions when cached Spring contexts take over 30 seconds to shut down. Shared singleton container destruction across contexts is the current investigation lead. This is tracked for Wave 8 fixture modernization; no test or coverage gate was disabled. It is not a verified production shutdown fix.

## Frontend and contracts

Executed from frontend/:

```bash
npm ci
npm run type-check --workspaces --if-present
npm run test:unit --workspaces --if-present -- --run
npm run build --workspaces --if-present
npx prettier --check customer-website/src/
npx prettier --check admin-dashboard/src/
```

All passed: customer 240 tests, admin 87 tests (327 total). From backend/proto/, buf lint and buf breaking --against '../../.git#branch=main,subdir=backend/proto' passed. Avro schemas remain unchanged; CI's Schema Registry compatibility test passed.

## Configuration and CI

Compose core/app and isolated E2E configuration validation passed. Kustomize base, production and ingress-tls overlays all rendered successfully. gh pr checks 45 --watch completed successfully on 75b83d2; all 16 checks passed, including CodeQL, Trivy and every integration-test job. No workflow files were changed.

## Isolated E2E and chaos

The first rebuilt Wave 7 stack passed full checkout, authenticated reconciliation/audit and measured Inventory latency assault. Its payment-outage case failed the unchanged 60-second bound: two original orders remained pending at the deadline. All five eventually resumed and were refunded by cleanup. It exposed an extra 30-second provider backoff after breaker-denied calls. The fix rechecks denied calls within 5 seconds (or a configured shorter delay), while preserving real-provider backoff, attempt/age budgets, original payment identity and uncertainty holds.

The rebuilt post-fix stack passed all three tests (zero failures/errors/skips; BUILD SUCCESS, 1m10s): FullOrderFlowE2EIT 6.899s, ServiceKillChaosIT 62.306s. The outage test's complete case took 58.340s including setup/checkout/cleanup; its unchanged assertion specifically measures all five original orders confirming and the breaker closing within 60 seconds of Payment restart. The latency case took 3.964s. All 13 isolated containers became healthy before testing.

Executed from the repository root:

```bash
infra/e2e/stack.sh build
infra/e2e/stack.sh up
infra/e2e/stack.sh test
infra/e2e/stack.sh down
```

The test script ran backend/mvnw verify -pl :e2e-tests -DskipE2ETests=false -De2e.enabled=true -Dchaos.enabled=true with tokens only in its process environment. Full flow additionally validates real client-credential reconciliation/audit via Gateway, RESERVED→COMMITTED quantities and a refunded return to the original quantities. The outage scenario verifies exact-once provider/payment/stock/audit/confirmation effects. A real 500ms service-layer latency assault is measured and disabled afterward.

Read-only post-test SQL for the seven new orders (IDs 15–21) found 7 CANCELLED orders, 7 REFUNDED payments for 7 distinct orders, all 7 fully refunded amounts, 7 RELEASED reservations, and exactly 7 RESERVE/7 COMMIT/7 RELEASE movements of one unit. No old order was counted as new evidence. After teardown, no isolated containers remain; all test volumes and the unrelated running ivysport-mysql container are preserved. The background Java compiler was resumed.

Combined current backend evidence is 977 passing tests (974 reactor plus 3 E2E/chaos); frontend independently passed 327 tests. The initial failed chaos run is retained as a failure record above, not counted as passing.

## Rollout and limits

No live realm or Kubernetes application was modified. The checked-in CD workflow and automated ArgoCD sync make main a deployment trigger. Provision Inventory/Notification confidential clients, roles and matching secrets in the existing realm, preserve/copy Product images into the persistent volume, drain writes and stop incompatible old stateful writers before coordinated deployment. Per-service Recreate is insufficient to coordinate dependencies. Existing realm startup imports do not update live clients.

Full finding status and migrations are recorded in docs/backend-remediation-waves-6-9.md. Payment and email providers remain mocks; this evidence does not validate a third-party payment gateway, external email delivery, load performance or live deployment readiness. Docker test teardown must preserve all volumes and the unrelated ivysport-mysql container.
