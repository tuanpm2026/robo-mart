# Automatic pending-payment recovery validation

Date: 2026-10-24 (Asia/Ho_Chi_Minh). Completed: full backend verification and isolated E2E/chaos passed.

## Behavior and guarantees

Order's recovery job automatically continues only the Payment step of `PAYMENT_PENDING` orders. It keeps the original reservation and the original order-ID idempotency key. PostgreSQL `FOR UPDATE SKIP LOCKED` claims one due order before the remote call; other worker replicas skip that row. A single transaction commits the payment ID, terminal status, history, success audit and status-change outbox. A worker crash before commit leaves the order pending for replay of the same payment result.

The worker keeps the persisted state `PAYMENT_PENDING` during the bounded RPC. It does not expose a transient `PAYMENT_PROCESSING` state to the compensation scanner. A timeout, unavailable service, incomplete success response or unexpected client error retains the reservation and schedules another attempt. Only a definitive decline permits cancellation, and cancellation waits for an acknowledged inventory release. An unavailable release is retried using the same declined payment result.

Initial checkout Payment timeout/interruption also retains pending state. Stale `PAYMENT_PROCESSING` orders are requeued for payment reconciliation instead of blindly cancelling a possibly settled charge. The status and age are rechecked under a row lock; the existing five-minute stuck threshold prevents startup recovery from interfering with another replica's live checkout.

Payment serializes order/key requests before charge and binds the key to order, user, amount and currency. Terminal payment results survive cache expiry/removal; the TTL never authorizes a new charge. The current provider is a mock: its charge/decline/refund operations now use a durable database ledger and an independent transaction, modeling provider-side idempotency across caller rollback and process recreation. A separate provider pool of two connections prevents nested transactions from starving the main Payment pool. An actual provider adapter must honor the same stable key and durable idempotency contract.

## Configuration and migrations

- `saga.payment-recovery.enabled=true` by default.
- `check-interval-ms=5000`, `initial-delay-ms=5000`, `retry-delay=30s`, `batch-size=20`.
- Initial held payments and subsequent transient failures become eligible after 30 seconds. Recovery uses the existing circuit breaker and lets the next eligible request probe it; it does not force the breaker closed.
- Payment and strict inventory-release RPCs used by recovery each have a five-second deadline; each claimed Order transaction has a 30-second timeout.
- Order migration V7 adds retry count/due time and a partial due-order index. Existing pending rows with null due time are eligible.
- Payment migration V3 adds provider operations and nullable legacy user/refund-receipt columns. V4 adds/backfills provider order-binding metadata and a unique index; a different key cannot charge an already-bound order even if the original Payment transaction rolled back. Existing completed/refunded payments remain replayable. Financial amounts/statuses are not deleted or rewritten; preexisting duplicate provider orders stop the uniqueness migration for review rather than silently deleting records.

## Isolation

This run uses the fresh fixed Compose project `robomart-java25-e2e-resume`, with alternate loopback ports and fresh project volumes. It does not mount either developer volumes or the prior `robomart-java25-e2e` test volumes. A baseline snapshot recorded 23 existing stopped containers and 60 existing volumes before provisioning. Final preservation checks and teardown results will be recorded below.

## Regression coverage

Focused Order recovery integration tests use real PostgreSQL transactions and mocked downstream gRPC peers. They exercise state/audit/outbox commit, retry timestamps, definitive and cached declines, failed release, invalid success, concurrent claims, stable-key ambiguous response replay, and stale-processing guards.

Payment integration tests exercise the actual durable provider ledger, concurrent request/provider instances, different keys for one order, payload rebinding, expired/missing cache, rollback after settlement, provider recreation, persistent decline and refund replay, legacy refunded rows and small-pool concurrency. These complement the Order tests; the isolated HTTP chaos scenario uses actual running Order/Inventory/Payment services.

The updated chaos scenario stops Payment before five checkouts and asserts the same five order IDs automatically become confirmed after restart, without submitting a fresh checkout to probe the breaker. It requires one payment, provider charge operation, original reservation, success audit, confirmation history/outbox and payment outbox per order. Teardown cancels/refunds/releases recovered orders and disables faults. Email delivery still uses the mock email provider; the latency assault remains service-layer instrumentation, not transport fault injection.

## Full backend verification

Executed from `backend/` using Java 25.0.4.1 and Maven Wrapper 3.9.11:

```bash
./mvnw clean install -T 1C -DskipTests -DskipITs -DskipE2ETests --no-transfer-progress
./mvnw verify -fae -DskipE2ETests -DskipBufLint=false --no-transfer-progress
./mvnw checkstyle:check --no-transfer-progress
```

All commands returned success. The clean install compiled/package-installed all 14 modules without running tests. Full verification completed at **13:36:41 ICT**, in **8 minutes 5 seconds**: **609 unit/consumer-contract tests and 166 integration/provider-contract tests (775 total)**, with **0 failures, 0 errors, 0 skipped** among those suites. All configured 80% line coverage gates passed; exclusions were unchanged. E2E is deliberately separate from this command.

Counts use individual XML `<testcase>` entries, cross-checked against Maven summaries; nested JUnit suite attributes can undercount cases.

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
| order-service | 131 | 31 | 89.62% |
| inventory-service | 79 | 21 | 83.53% |
| payment-service | 49 | 26 | 83.95% |
| notification-service | 90 | 7 | 91.65% |

This includes all 12 `PendingPaymentRecoveryIT` cases and all 13 `PaymentIdempotencyIT` cases, as well as the existing gRPC/Saga/cancellation/provider-contract suites.

## Isolated E2E and chaos result

Executed from the repository root after packaging current JARs:

```bash
infra/e2e/stack.sh build
infra/e2e/stack.sh up
infra/e2e/stack.sh test
infra/e2e/stack.sh down
```

All images built and all 13 containers reached healthy before tests. The test script ran `./mvnw verify -pl :e2e-tests -DskipE2ETests=false -De2e.enabled=true -Dchaos.enabled=true` from `backend/`. Result: **3 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS** at **13:39:32 ICT**, in **54.085 seconds**.

| Suite | Cases | Duration | Result |
| --- | ---: | ---: | --- |
| FullOrderFlowE2EIT | 1 | 4.792 s | Passed |
| ServiceKillChaosIT | 2 | 48.18 s | Passed |

The five original held orders resumed automatically within the shared 60-second restart/health/breaker/recovery budget. No new checkout was submitted to trigger breaker recovery. Each held order had exactly one completed payment, provider charge operation, reservation movement, forward-step success audit, confirmation history/event and payment outbox. The other chaos case applied a real measured 500 ms service-layer latency assault. The full-flow case verified search/cart/JWT/RBAC, confirmed checkout, asynchronous notification, cancellation/refund and stock release.

Post-test probes verified all seven test orders were CANCELLED, all seven payments REFUNDED (seven distinct order IDs), seven completed provider charge operations and seven refund operations, no duplicate provider order bindings, seven RESERVE/seven RELEASE movements and zero reserved units. The seven confirmations remain in history. Chaos Monkey was disabled with all assault flags false, and Payment health was UP before teardown.

Combined executed evidence: **778 tests passed** (775 backend plus 3 E2E/chaos). Raw summaries, exact counts/coverage, runtime probes and E2E Maven output are retained in [payment-pending-resume-2026-10-24](payment-pending-resume-2026-10-24/).

## Preservation and limits

After teardown, all **23 original container IDs/states and 60 original volume names were preserved**. No `robomart-java25-e2e-resume` containers remained; its test volumes were retained. This includes preserving all prior E2E data as well as developer data. No default/developer stack or prior E2E project was started. No existing volume was mounted by the fresh project and no volume-removal command was used. Docker credential-helper workarounds used a temporary empty `DOCKER_CONFIG`; user credentials and local `.env` files were unchanged.

No deployment or remote GitHub Actions run was triggered. Frontend and k6 were unchanged/unexecuted. The current charge/refund provider and email transport remain mocks; database durability and real inter-service recovery were tested, rather than a third-party payment/email service. No load-performance claim is made.

## Publication validation

Before publishing, the complete working-tree snapshot was committed and rebased onto `origin/main` at `0366d1f`. All previously tested backend Java sources, tests, migration SQL and dependency versions were preserved byte-for-byte. Upstream frontend/GitOps updates and Docker `COPY` path corrections were retained; the proto module also retained upstream's Exec Maven Plugin 3.6.3.

After rebase, the full 14-module install/compile (tests skipped) passed at 14:44:47 ICT. Proto verification with `-DskipBufLint=false` and Checkstyle passed at 14:45:00 ICT, including all eight proto tests. `git diff --check origin/main...HEAD` passed. The full 775-test backend and three-test E2E/chaos runs above were not repeated after these build-plugin/Docker-path-only differences. Test-log trailing whitespace was normalized for version control without changing results.
