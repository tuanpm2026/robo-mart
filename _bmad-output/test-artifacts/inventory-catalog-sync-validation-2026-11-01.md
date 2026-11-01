# Inventory/catalog synchronization and browser validation — 2026-11-01

## Scope

Local source validation on `codex/inventory-catalog-sync`, based on main
`50f2628552c0d8256eadff98b18775f3422abf75`. This report does not establish that the
new application code is deployed on markserver. Monitoring protection was deployed
separately through PR #51; its [validation report](monitoring-access-validation-2026-11-01.md)
records the monitoring storage incident and loss of old Prometheus/Tempo history.

Inventory is the stock authority. New versioned absolute snapshots propagate through
the transactional Inventory outbox, Kafka, Product's persisted version fence and
Product outbox into catalog/search/cache. New products initialize an Inventory item
at zero without trusting Product's submitted stock. A new migration publishes
retained stock without modifying quantities. See [the design/runbook](../../docs/inventory-catalog-sync.md).

## Executed automated checks

| Check | Result |
| --- | --- |
| `./mvnw -B -ntp verify -pl :outbox-lib,:events,:product-service,:inventory-service -DskipE2ETests` | Outbox/events passed; Product failed on a local Docker/Orca IPv4 port collision. |
| `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true ./mvnw -B -ntp verify -pl :product-service,:inventory-service -DskipE2ETests` | Passed Product/Inventory unit + integration suites and coverage gates. |
| `./mvnw -B -ntp clean install -DskipTests -DskipITs -DskipE2ETests` | All 18 modules built/installed; this command skips tests. |
| `infra/e2e/stack.sh prepare`, serial Compose image builds, `infra/e2e/stack.sh up` | Isolated stack prepared, built and healthy. |
| `infra/e2e/stack.sh test` | 5 E2E tests passed, including stock/catalog, full order lifecycle, image storage and 2 chaos cases. |
| `./mvnw -B -ntp verify -pl :api-gateway -DskipITs -DskipE2ETests` | 65 tests passed and coverage gate passed after the browser CORS fix. |
| `npm run type-check --workspaces --if-present` | Both apps passed. |
| `npm run test:unit --workspace=customer-website -- --run` | 240 tests passed after registering ConfirmationService. |
| `npm run test:unit --workspace=admin-dashboard -- --run` | 89 tests passed, including new real-table filtering/pagination regression tests. |
| `npm run build --workspaces --if-present`, then each changed app's `build` | Both apps built with type checking; final app builds passed after later fixes. |
| Focused `npx prettier --check` on changed UI source/tests; `git diff --check` | Passed. |

Unit/integration counts and line coverage were preserved before the clean reactor
build in [the evidence directory](inventory-catalog-sync-2026-11-01/). Product's
recorded line coverage is 850/900; Inventory 708/746; Outbox 100/102. No coverage gate
was disabled for these modules. E2E has its existing no-coverage configuration.

Initial failed runs are not counted as passing: stale JaCoCo data required a clean
build; a Redis random port overlapped Orca's IPv4 listener (IPv6 reached Docker);
and the first new E2E polled anonymous catalog reads too fast and exhausted its
rate limit. The corrected E2E polls with a valid customer token once per second
and retains a separate anonymous stock assertion. Gateway limits remain enabled.

## Browser execution

Chrome used Vite source UIs on ports 5173/5174 against the isolated
`robomart-java25-e2e-resume` Compose project: Gateway 18080, Keycloak 18180 and object
storage 19002. Public committed test users were used through actual OIDC login;
no real payment provider or private customer data was used. Other Docker projects
were left running.

1. A customer session was denied the admin dashboard, as expected.
2. Admin `demo-admin` created product **53**, SKU **UI-STOCK-20261101**, name
   **UI Stock Validation 20261101**, price **19.95**, category Electronics.
3. Admin attached a valid 96×96 PNG fixture through the file chooser before save.
   The public product page rendered the uploaded image.
4. Inventory automatically created zero stock. The repaired server paginator
   exposed page 3; inline restock changed available/total from 0 to **5**.
   Database reads independently confirmed Product stock 5/source version 1 and
   Inventory available 5/reserved 0/version 1.
5. Customer `testcustomer` found the product through search, opened detail showing
   **5 left**, added one unit and completed demo checkout with fictional address
   and the displayed mock card fixture. Order **47** was **CONFIRMED** for **19.95**.
6. A separate anonymous product tab showed **4 left** after checkout.
7. Customer opened My Orders → order 47 → Cancel Order and accepted the application
   confirmation. The UI showed **Cancelled**; anonymous detail returned to **5 left**.
   Inventory was available 5/reserved 0/version 4 after cancellation.

Browser execution found and repaired: protected-route CORS preflight rejected before
authentication; SockJS `global` reference in Admin's browser bundle; missing Product
name-filter model; disconnected Inventory server pagination; and unregistered
customer ConfirmationService. API-only tests had not exposed these browser failures.

Screenshots are saved in the local Codex visualization directory for this task and
were shown inline during execution: `admin-inventory-restock.png`,
`customer-stock-5.png`, `customer-order-confirmed.png`,
`customer-order-cancelled.png`, `customer-stock-restored.png`.

## Limits and follow-up

- These are local fixture results, not a real PSP/email integration or load test.
- The frontend still does not preserve a checkout idempotency key across HTTP
  retries. Its busy state only prevents double submission within that attempt.
- Successful checkout resets Pinia's cart but does not clear the Redis cart;
  the item reappeared after a page reload. This remains tracked follow-up work.
- Restoring authentication on a protected deep link returned to home in this
  browser run. In-app My Orders navigation succeeded after initialization.
- Inventory API fixes order by product ID. The UI now pages that result and no
  longer offers misleading client-only sorting. Product-name enrichment still
  fetches up to 1000 products; larger catalogs need server-side enrichment.
- The mock order was cancelled and stock restored. Disposable product 53 and its
  image remain in the isolated stack for evidence; they were not created in demo.
- Application deployment and exact-image provenance require separate release
  verification after CI/merge. Do not call the new consumer deployed based only
  on this report.
