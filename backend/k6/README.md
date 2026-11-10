# k6 checkout experiments

## Flash sale: 100 customers, one stock unit

This experiment exercises the current **external Checkout Saga** through the real Gateway,
Keycloak, Order, Inventory, Payment and Kafka in the isolated E2E stack. Payment remains the
existing durable mock provider. It is a correctness exercise under concurrent load, not a
production capacity benchmark.

Run commands from the repository root. Prerequisites: JDK 25, Python 3, Docker Compose,
approximately 8 GiB allocated to Docker, and [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/).
On macOS, k6 can be installed with `brew install k6`.

```bash
# Package current applications. Tests are skipped by this packaging command.
(cd backend && ./mvnw install -DskipTests -DskipITs -DskipE2ETests)
infra/e2e/stack.sh build
infra/e2e/stack.sh up

# Optional smaller rehearsal, followed by the full experiment.
python3 backend/k6/run_flash_sale.py --customers 5
python3 backend/k6/run_flash_sale.py

# Repeat with Payment stopped before the burst and restarted after stock is held.
python3 backend/k6/run_flash_sale.py --payment-outage

# Stop the fixture while retaining its volumes and recorded business data.
infra/e2e/stack.sh down
```

Run experiments **sequentially**, separately from Java/browser E2E and other chaos tests.
The runner requires the exact `robomart-java25-e2e-resume` Compose labels and fixed loopback
Gateway/Keycloak bindings. It checks external Saga admission before creating fixtures.
There is no production URL or arbitrary Compose target override.

### What the runner does

1. Create a fresh product and one disposable Keycloak customer per virtual user, with the
   actual `CUSTOMER` realm role. Existing users and products are preserved.
2. Wait for Inventory's asynchronous zero-stock bootstrap, then restock exactly **one** unit
   through the Inventory admin API. No Product SQL seeding or cross-service writes are used.
3. Acquire tokens before the measured burst. k6 gives each customer one iteration, aligns
   their start time, and submits one checkout with a distinct idempotency key. Requests are
   concurrent; identical network arrival times are not guaranteed. See k6's
   [per-VU executor](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/per-vu-iterations/).
4. Replay each accepted checkout with the **same key and payload** and require the same
   order ID. Ambiguous HTTP failures retry with that original key. HTTP `200`, `201` and
   `202` are accepted responses; acceptance alone is not checkout success.
5. Poll each original order to `CONFIRMED` or `CANCELLED`, with a bounded deadline. Failed
   authentication, malformed responses, replay mismatches and timeouts fail the run.
6. Probe the service databases using server-enforced read-only queries. Require exactly
   one confirmed order, one application payment, one completed provider charge, and one
   RESERVE/COMMIT movement. Every other customer's order must be cancelled, with the Saga's
   durable reason `Inventory rejected reservation`, released reservation and no stock movement.
7. Cancel the winner through its customer API. Await the full provider refund, one RELEASE
   movement, all reservations released, and inventory restored to available/reserved/total
   `1/0/1`. Soft-delete the fixture product and remove only the newly created customers.

The Order API can expose the generic cancellation reason `Checkout could not be completed`.
The runner reads the Saga's reason to distinguish insufficient stock from payment or other
failures; it never counts every cancellation/HTTP error as an out-of-stock response.

### Payment outage and recovery

With `--payment-outage`, the runner stops only the label-verified fixture Payment container
before k6 starts. It waits until all original orders exist and the one unit is reserved,
then records evidence: no payment/charge, no confirmed orders, stock `0/1/1`. Losers can
already be `CANCELLATION_PENDING` because closing charge admission also needs Payment.
It restarts Payment and requires the **same order IDs** to finish without a new checkout.
Finally, the same refund/stock cleanup checks apply. Payment restoration runs even when the
load process or an assertion fails. Cleanup errors fail the run and retain identities when
compensation cannot be confirmed, rather than reporting success.

This models a service outage/restart. It does not simulate a lost committed RPC response,
network packet loss or a real payment provider; the separate Saga durability E2E suite
covers the committed-response boundary.

### Results and interpretation

The command returns nonzero if k6, database assertions, or cleanup fails. By default it
writes JSON to ignored `backend/k6/results/flash-<uuid>.json`. Use `--output /absolute/path.json`
to choose a new file; existing evidence is not overwritten. Tokens are confined to a private
temporary file and are absent from the evidence. Business rows remain in retained fixture
volumes for diagnosis; cleanup cancels/refunds them rather than deleting financial history.

| Evidence | Expected before cleanup | Expected after cleanup |
| --- | --- | --- |
| Orders for this product | 100: one CONFIRMED, 99 CANCELLED | All 100 CANCELLED |
| k6 replay mismatches / unexpected outcomes | 0 / 0 | Not rerun |
| Completed provider charges | Exactly one, for the winner, USD 9.99 | Same original charge |
| Provider refunds | None | Exactly one full refund |
| Inventory available / reserved / total | 0 / 0 / 0 | 1 / 0 / 1 |
| Stock movements | Winner: one RESERVE and one COMMIT | Also exactly one RELEASE |
| Saga manual review | None | None |

`checkout_completion_ms` measures from just before POST to the first observed confirmation,
including network/replay/polling time. There is only **one winner** per sale, so a percentile
from one run is not an SLA or a meaningful capacity estimate. The outage experiment includes
restart/recovery time. Correctness thresholds are enforced; this exercise imposes no invented
latency target. The full k6 metrics are recorded through
[`handleSummary`](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/).

Options: `--customers 2..100` (default 100), `--timeout 60..600` seconds (default 180 per
checkout), `--payment-outage`, and `--output`. Provisioning and cleanup are outside the k6
measurement. Slow asynchronous consumers get separate bounded reconciliation waits.

### Learning checkpoints

Before running, predict the result, then explain it using `beforeCleanup`, `duringOutage`
and `afterCleanup` in the JSON:

- Why can all 100 POSTs be accepted while only one customer buys the item?
- Which operation prevents two customers from reserving the same last unit?
- Why does replaying the same HTTP request not create a second order or charge?
- Why must an unknown payment outcome keep stock reserved?
- Why does cancelling a paid order require both a refund and inventory release?
- What would you need to change in this experiment to measure throughput or p95 latency?

The assertion regression tests deliberately corrupt evidence (extra charge/order, oversell,
missing movement, payment failure disguised as stock rejection, incomplete refund) and
require the experiment to fail. Run them without Docker or k6:
The Backend CI build job also runs both regression suites and the script syntax check.

```bash
python3 -m unittest discover -s backend/k6 -p 'test_*.py' -v
node --experimental-vm-modules --test backend/k6/*.test.cjs
node --check backend/k6/scripts/flash-sale.js
```

The [local validation record for 2026-11-10](../../_bmad-output/test-artifacts/flash-sale-validation-2026-11-10.json)
contains the executed 5-customer rehearsal, both 100-customer experiments, provider/stock
evidence, regression checks and runtime image identities.

## Concurrent orders: enough stock for every customer

This is a separate **closed burst** experiment: one fresh product, N stock units and N
fresh customers, each placing one order. Run at 25, 50 and 100 customers. Every order must
reach CONFIRMED, with one original provider charge, one reservation and one RESERVE/COMMIT
movement per order. Cleanup verifies N complete refunds and N releases before removing its
own product and customers. The fixture, stock API, isolation checks and cleanup hooks are
shared with the flash-sale runner.

```bash
# Start the isolated stack using the preparation commands above.
python3 backend/k6/run_concurrent_orders.py

# Compare one explicit local tuning change, with identical load and SLO.
python3 backend/k6/run_concurrent_orders.py --worker-interval-ms 25

# Smaller rehearsal or an individual repeat.
python3 backend/k6/run_concurrent_orders.py --levels 5
python3 backend/k6/run_concurrent_orders.py --levels 100
```

The default worker interval remains **500 ms**. The tuning option accepts only 500 or 25 ms
and recreates only the label-verified isolated Saga service, with no pending fixture workflows.
It restores the original interval on exit, including failures. A restoration failure makes
the command fail and is recorded. Production configuration and Saga concurrency/lease/retry
semantics are unchanged. Run this sequentially with all other suites using the fixture.

### Keep correctness and performance verdicts separate

The original completion objective is retained: **p95 after API acceptance <3000 ms**.
HTTP errors must remain below 1%, all N orders must succeed, and all N completion samples
must exist. These are k6 thresholds, not informational labels. A threshold failure produces
a nonzero exit status. No option relaxes or disables the completion objective.

The runner still verifies financial/inventory evidence and cleans up after an SLO failure.
It records `correctness: passed`, `performance: failed`, and `status: failed` when business
data is correct but the completion objective is missed. It proceeds to the next requested
load level after such an SLO miss; business or cleanup failure stops subsequent levels.
An overall run with any failed level remains failed. Passing the harness regression tests
in CI does not assert that this local performance target was met.

Results default to ignored `backend/k6/results/concurrent-<uuid>.json`; `--output` selects a
new file. `--levels` accepts customer counts 2..100; `--timeout` accepts 60..600 seconds and
bounds completion waiting. Increasing that wait does **not** change the 3-second objective.
The default levels are 25, 50, 100. Use fresh fixture data on every repeat.

### Read the three clocks

| Measurement | Start | Finish | Interpretation |
| --- | --- | --- | --- |
| `order_acceptance_ms` | Before first POST | Accepted order response | Includes ambiguous retries with the original key/payload |
| `saga_completion_time` | Accepted response | First observed CONFIRMED | Existing <3s p95 objective; includes status polling overhead |
| `checkout_completion_ms` | Before first POST | First observed CONFIRMED | Customer-observed completion, including API admission |

Only successful confirmations enter latency trends. Exact success/sample-count thresholds
prevent missing or failed customers from disappearing and making p95 look artificially good.
The runner verifies that the observation IDs match the actual orders and that each virtual
user contributes exactly one observation. It does not treat 202 as a successful checkout.

`confirmedOrdersPerSecond` is N divided by the interval from the first submission to the last
observed confirmation. Provisioning, token acquisition, a two-second release barrier, probes
and cleanup are excluded. Submission spread is recorded. This is an effective burst-drain
rate, **not sustained capacity or an open-arrival-rate test**. One run per level can expose
queueing, but warmup, polling, shared host resources and sample size still affect comparisons.
Repeat runs on a controlled environment before making capacity/SLA claims.

### Diagnose waiting before changing code

Each `LOAD_ORDER` record includes its accepted response trace ID. `traceCorrelation` joins
the order/trace pair with Order's CREATE audit rows and also checks structured logs. It
retains correlation metadata without copying message bodies or credentials. Successful
EXTERNAL admission currently has no per-order INFO message, so zero matching normal log
lines can coexist with complete CREATE audit correlation. The fixture samples tracing at
zero: this is request/audit/log correlation, **not exported distributed-span timing**.

`serverTimeline` uses read-only queries against the same PostgreSQL server to join:

1. Order `created_at` and the CONFIRMED status-history timestamp.
2. Saga `created_at`, showing when the workflow reached its durable inbox.
3. `saga_steps.dispatched_at` for reserve, charge and confirmation intents.

The reported stage differences include scheduler/queue waits and previous operation work.
Intent persistence timestamps are **not RPC durations**. Compare them with the client clock
and use the saved order/trace IDs to investigate a specific slow checkout. The SQL probes
run after the measured burst and do not add probe traffic during the measurement.
Each stage's p95 can describe a different order; adding stage p95 values does not produce
the end-to-end p95.

The worker has four slots and polls every 500 ms by default. It can admit at most four steps
per scheduler tick, giving an ideal ceiling of eight admitted steps/second before RPC/DB
costs. A successful checkout needs several steps. A fast HTTP response can therefore coexist
with a long workflow queue. Trying a 25 ms interval tests that hypothesis; it can increase
DB polling and expose a different bottleneck. It is a local experiment, not a production
tuning prescription or proof that all time is spent waiting for this scheduler.

Suggested learning sequence: predict the 25/50/100 results; run the baseline; compare API
p95 with completion p95; inspect one slow order's timeline and trace ID; change only the
worker interval; rerun identical levels; check both correctness and SLO verdicts. Explain
why a throughput improvement does not automatically mean the 3-second objective is met.

The [k6 custom metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/create-custom-metrics/)
and [threshold documentation](https://grafana.com/docs/k6/latest/using-k6/thresholds/) describe
the metric aggregation and nonzero-exit behavior used here. Both JavaScript suites and all
Python evidence/measurement regressions run in Backend CI; live load tests remain explicit
local experiments requiring the prepared stack.

### Observed local comparison, 2026-11-10

The [execution record](../../_bmad-output/test-artifacts/concurrent-orders-validation-2026-11-10.json)
contains original timing-source hashes, runtime image identities, all verdicts, per-stage
SQL percentiles and 350 verified order/trace-audit matches. Complete raw runs remain in the
ignored local `results/` paths recorded there.

| Customers | Worker polling | Acceptance p95 (ms) | After-acceptance p95 (s) | Effective burst orders/s | <3s p95 SLO |
| --- | --- | --- | --- | --- | --- |
| 25 | 500 ms | 92.60 | 13.72 | 1.80 | Failed |
| 50 | 500 ms | 172.30 | 25.49 | 1.91 | Failed |
| 100 | 500 ms | 327.10 | 47.46 | 1.86 | Failed |
| 25 | 25 ms | 295.20 | 10.43 | 2.28 | Failed |
| 50 | 25 ms | 212.65 | 9.70 | 3.34 | Failed |
| 100 | 25 ms | 333.05 | 8.01 | 7.88 | Failed |

All six levels passed the business and cleanup assertions: every customer got one confirmed
order and one provider charge, followed by one full refund and stock release. **Both suite
commands returned 1** because the unchanged latency objective was missed; no other k6
threshold failed. Automated harness regressions passed independently of this SLO verdict.

At 100 customers, observed completion p95 fell from 47.46s to 8.01s and effective burst drain
rate rose from 1.86 to 7.88 orders/s. SQL inbox-to-reserve-dispatch p95 fell from about
12.12s to 0.37s, supporting the scheduler-queue hypothesis. Remaining time before durable
Saga inbox admission and between charge/confirmation intents needs further instrumentation;
these observations do not establish that a particular RPC itself took that long.

The tuned 100-customer median after acceptance was 2.75s while p95 was 8.01s: looking only
at the median would conceal the slowest customers. The 25 ms suite restarted the worker,
so its first 25-customer run includes warmup effects; the two 100-customer cases both follow
25/50-customer runs. These single-run results can be non-monotonic and are not evidence that
higher concurrency improves capacity. Production defaults remain unchanged and the isolated
worker was restored to 500 ms after the comparison.
