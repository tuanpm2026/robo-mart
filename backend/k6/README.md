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
node --experimental-vm-modules --test backend/k6/flash-sale.test.cjs
node --check backend/k6/scripts/flash-sale.js
```

The [local validation record for 2026-11-10](../../_bmad-output/test-artifacts/flash-sale-validation-2026-11-10.json)
contains the executed 5-customer rehearsal, both 100-customer experiments, provider/stock
evidence, regression checks and runtime image identities.

## Legacy concurrent-orders benchmark

`scripts/concurrent-orders.js` is the earlier benchmark and is **not modernized by this
flash-sale change**. It assumes pre-seeded `testuser001@robomart.com` through
`testuser100@robomart.com`, uses `helpers/auth.js`, and still expects synchronous `200/201`
checkout admission. Its counters are not evidence for the current asynchronous Saga.
Update its admission handling, authoritative fixture seeding and payment/stock verification
before using it for capacity conclusions. Product's stock projection is not the authoritative
place to seed available inventory.
