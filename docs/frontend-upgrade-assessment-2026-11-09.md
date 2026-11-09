# Robo-Mart frontend upgrade assessment — 2026-11-09

> **Latest local implementation status (2026-11-09):** The requested responsive/accessibility UI batch is implemented locally on top of the three earlier regression fixes. This document's original findings and baseline measurements remain historical; see [Latest local UI status](#latest-local-ui-status-2026-11-09) and the [implementation evidence](../_bmad-output/test-artifacts/frontend-uiux-upgrade-2026-11-09/README.md). No changes have been committed, integrated or deployed.


## Executive assessment

**Keep the existing Vue architecture and upgrade the buying and administration experience incrementally.** The main constraint is incomplete interaction and recovery behavior, rather than an obsolete framework stack. Both applications already use Vue 3, TypeScript 6, Pinia 3, PrimeVue 4, Tailwind 4 and Vite 8. Replacing these frameworks would add migration work without directly fixing the observed problems.

The assessed baseline installs, type-checks, passes **329 unit tests**, builds, and passes read-only lint/format checks. Those results coexist with reproducible mobile and keyboard defects, incomplete admin catalog pagination, checkout recovery gaps, and dependency advisories. Green unit tests are useful evidence of a maintainable starting point, not proof that complete browser workflows work.

**Independent review:** The upgrade direction remains supported. Three additional behavioral probes reproduced the cart rollback defect, stale search responses, and an omitted case where clearing a filter still sends its old value. The existing 329 tests and both type checks passed again after removing the temporary probes. Evidence is now retained in the [review evidence directory](../_bmad-output/test-artifacts/frontend-upgrade-review-2026-11-09/README.md). The 16 findings are assessment groups with different evidence levels, not 16 independently reproduced runtime defects. F01/F02 describe baseline defects with mitigations already present in the uncommitted original checkout; their integrated release validation remains outstanding.

**Subsequent local implementation:** The three authorized regressions (F08 snapshot rollback, F11 search response ordering and F11 filter clearing) are now **fixed locally, not integrated** in this assessment worktree. Final checks passed **356 unit tests** (267 customer, 89 admin), both type checks/builds and read-only lint/format on changed files. The same final targeted tests fail against frozen baseline source (**25 failed / 22 passed**) and pass against fixed source (**47 passed**). See the [fix report, red/green logs and self-review](../_bmad-output/test-artifacts/frontend-regression-fixes-2026-11-09/README.md). Baseline observations below remain historical evidence; other F11 recommendations and general cart concurrency remain open.

Recommended order:

1. Reconcile the existing checkout recovery work with its backend contracts and preserve its tests. Do not implement a competing recovery mechanism.
2. Address dependency advisories in small reviewed updates; establish browser and accessibility gates.
3. Deliver a focused mobile/keyboard upgrade for discovery → cart → checkout and admin products/orders.
4. Consolidate tokens and repeated interaction patterns after those flows establish the needed contracts.
5. Consider a larger visual refresh, newer component-library major, or SSR only when product requirements and measurements justify them.

There is **no confirmed P0 exploit or incident established by this assessment**. P1 findings below warrant attention before treating this baseline as ready for a broader customer rollout. Security audit severities describe packages and their prerequisites; they are not application exploit confirmations.

## Scope, provenance and method

| Item | Assessed state |
| --- | --- |
| Assessment worktree | `/Users/tuanphung/orca/workspaces/robo-mart/codex-frontend-upgrade-assessment` |
| Branch | `tuanpm2026/codex-frontend-upgrade-assessment` |
| Commit | `4ec50bbaa04933c43a37066f88c293e056c24012` |
| Commit description/date | `Bound Tempo flush memory and probe readiness [skip ci]`, 2026-11-01 15:34:05 +07:00 |
| Orca provenance | Existing independent worktree, `created-by-cli`, base `refs/remotes/origin/main`, no parent; confirmed with `orca worktree current --json` |
| Original checkout, read only | `/Users/tuanphung/work/robo-mart`, branch `codex/extract-checkout-saga`, same HEAD, substantial uncommitted/untracked work |
| Local execution | macOS, Node `v24.21.0`, npm `11.19.0`; CI specifies Node `22` |
| Application scope | `frontend/customer-website`, `frontend/admin-dashboard`, `frontend/shared`, manifests/lockfile, Vite/Vitest/TypeScript/ESLint, frontend CI and the original checkout's draft browser CI |

All unqualified file/line references below refer to the **baseline commit**, with paths relative to the repository root. `ORIGINAL:` explicitly identifies uncommitted files in the original checkout; their line numbers are observations of that mutable working copy, not committed provenance. Markdown links resolve to repository files; `:line` references supply the source locations.

The worktree was initially clean. The initial assessment changed documentation only; installation/build outputs are ignored local artifacts. The subsequent authorized fix phase changes only the three customer regressions, their tests, targeted filter-removal controls and local reports described above. No commit, push, merge, original-checkout write, stack start, dependency update or backend data mutation was performed. Initial assessment browser checks used private Chrome storage, alternate preview ports, synthetic catalog/OIDC data, and intercepted API requests; the subsequent fix checks use mocked APIs/jsdom rather than real-stack E2E.

Implementation/configuration/tests were read before conclusions. Context consulted includes [system flow](development-system-flow.md), [architecture](../_bmad-output/planning-artifacts/architecture.md) (frontend decisions around lines 429–455), [sprint status](../_bmad-output/implementation-artifacts/sprint-status.yaml) (lines 148–224), and [deferred work](../_bmad-output/implementation-artifacts/deferred-work.md) (current-baseline section, lines 18–27). Relevant story acceptance criteria and implementation notes reviewed: 1.6, 1.7, 3.2, 4.7, 5.1, 5.2, 5.5 and 7.1. Their desktop grids, hover controls and fixed admin shell explain some current decisions; they do not establish mobile readiness. No BMAD workflow was activated or story status changed.

## Inventory and baseline

### Applications and architecture

| Area | Existing capability | Implication |
| --- | --- | --- |
| Customer | 31 Vue SFCs, 47 TypeScript files including 27 test files; home/search/details, cart, OIDC, four-step checkout, confirmation, order list/details/cancellation | Improve the existing purchase funnel; no need to rebuild routing or state management |
| Admin | 35 Vue SFCs, 41 TypeScript files including 19 test files; products/images, inventory/restock, order fulfillment, dashboard, reports, health and DLQ | Retain PrimeVue tables/drawers; fix data access and operational interactions |
| Shared | 2 Vue components (`EmptyState`, `OrderStateMachine`), 7 TypeScript files; palette, typography, spacing, shadows and two Aura presets; no own test/build scripts | A usable starting point, not yet a complete shared design system |
| Data | Typed Axios modules → gateway REST endpoints; Pinia feature stores; admin STOMP/SockJS for live events | No evidence that adding GraphQL, another query library or a new state framework is required |
| Auth | `oidc-client-ts` Authorization Code flow, session storage, customer renewal mutex, admin async guard and role mapping | Preserve OIDC and gateway authorization; improve startup/session lifecycle and redirects |
| Build | npm workspaces, lockfile, Vite 8 Vue/Tailwind plugins, lazy route imports, `vue-tsc --build` including test project references | Keep these boundaries and test both consumers when shared changes |
| CI | Type check, lint, Prettier, unit tests/JUnit, build, customer-only informational axe step, release-policy regression tests | Good foundation; browser and meaningful accessibility enforcement remain gaps |

Counts exclude dependencies and generated output. Shared components are exercised through consumers in places (for example customer `EmptyState.spec.ts`); absence of a standalone shared test script does not mean they have no coverage.

Both app TS configs enable `noUncheckedIndexedAccess`; app and test project references are present. Lazy routes already exist. Customer cards reserve image space with `aspect-ratio` and use lazy images. Both CSS entry points include focus-visible styles and reduced-motion handling. Customer provides semantic landmarks and a skip link. These are strengths to retain.

### Dependency inventory

Versions here are **resolved from `frontend/package-lock.json`**, not claims about the newest available releases.

| Group | Baseline resolved versions | Assessment |
| --- | --- | --- |
| Runtime/router/state | Vue `3.5.39`, Vue Router `5.1.0`, Pinia `3.0.4` | Already modern; keep current architecture and review security patches |
| Components/themes | PrimeVue `4.5.5`, `@primevue/themes` `4.5.4`; transitive `@primeuix/themes` `2.0.3` | Theme alias is explicitly deprecated in package metadata and install output |
| CSS | Tailwind CSS and `@tailwindcss/vite` both resolve `4.3.1` | Already CSS-first v4; customer manifest's `^4.2.2` is a range, not the installed version |
| API/auth | Axios `1.18.1`, `oidc-client-ts` `3.5.0` | Retain clients; security update and error/session contracts need attention |
| Customer forms | VeeValidate `4.15.1`, Yup `1.7.1` | Existing schemas can support the MVP |
| Admin visualization/live data | Chart.js `4.5.1`, STOMP.js `7.3.0`, SockJS `1.6.1` | Keep unless measurements or supported transports justify a change |
| Build/type tools | Vite `8.1.0`, plugin-vue `6.0.7`, TypeScript `6.0.3`, vue-tsc `3.3.5` | Already upgraded; separate tool patching from UI changes |
| Tests/lint | Vitest `4.1.9`, Vue Test Utils `2.4.11`, jsdom `29.1.1`, ESLint `10.6.0`, Prettier `3.9.1` | Functional locally; patch advisories and align supported Node versions |

App engines allow `^20.19.0 || >=22.12.0`, but locked ESLint/jsdom require `^20.19.0 || ^22.13.0 || >=24`. Thus Node 22.12 and Node 23 can satisfy the app range without satisfying the complete toolchain. Both Node configs extend Node 24 types/config; CI uses floating Node 22. This is a reproducibility/declared-support mismatch, not a failure observed on local Node 24.

## Findings with evidence

Priority: **P1** = purchase/admin correctness, essential usability, or release confidence; **P2** = next iteration reliability/maintainability/performance; **P3** = optional product investment. Evidence classes: **browser** = observed against compiled baseline with fixtures; **source** = direct code/config behavior; **audit** = dependency metadata/advisories; **opportunity** = proposed benefit that needs product or measurement validation.

### F01 — P1: checkout retry and cart completion are incomplete in baseline; existing work addresses them

**Source:** [orderApi.ts](../frontend/customer-website/src/api/orderApi.ts):19–21 posts without `Idempotency-Key`. [useCheckoutStore.ts](../frontend/customer-website/src/stores/useCheckoutStore.ts):49–103 creates a fresh request per placement and resets only in-memory cart state after navigation. Backend [OrderRestController.java](../backend/order-service/src/main/java/com/robomart/order/web/OrderRestController.java):41–66 already accepts an optional key and can return an accepted/pending result. [useCartStore.ts](../frontend/customer-website/src/stores/useCartStore.ts):96–100 does not consume the server cart.

A response lost after server commit can lead to a new order when the shopper retries; resetting Pinia alone lets purchased items return on a later fetch. Baseline [StepConfirm.vue](../frontend/customer-website/src/components/checkout/StepConfirm.vue):19–94 also has no generic error rendering although the store sets one. Source establishes the missing protections; duplicate charging was **not** reproduced here.

**Disposition:** Adopt/review the original checkout's persisted key + frozen payload + server snapshot acknowledgement, together with its backend implementation and E2E suite. Do not substitute a blanket cart DELETE: it could remove items added after the purchase. See the original-checkout reconciliation below.

### F02 — P1: customer protected deep links race authentication restoration

**Browser + source:** [main.ts](../frontend/customer-website/src/main.ts):17–20 installs the router before `initAuth()` at lines 45–49. [router/index.ts](../frontend/customer-website/src/router/index.ts):65–73 synchronously redirects unauthenticated protected navigation to `/`. A fresh document at `/orders/1?assessment=true#details` with a synthetic valid stored OIDC session ended at `/` in the isolated smoke check.

The customer auth service already saves/consumes safe relative return paths; startup ordering prevents that existing capability from solving this case. **Disposition:** Use the original checkout's auth-before-router initialization and protected `fullPath` login redirect; verify query/fragment preservation with real Keycloak before release.

### F03 — P1: mobile layouts need deliberate adaptation across the funnel and admin

**Browser + source:** [HomeView.vue](../frontend/customer-website/src/views/HomeView.vue):85–95 has four columns, then three below 1279px, with no smaller breakpoint. At an emulated 390px width it rendered three **98px** columns in a 342px content area; names and ratings were visibly cramped. At 1440px the grid rendered four 290px columns. [SearchResultsView.vue](../frontend/customer-website/src/views/SearchResultsView.vue):162–176 keeps a sidebar and three-column results. [DefaultLayout.vue](../frontend/customer-website/src/layouts/DefaultLayout.vue):208–214 uses one header row, leaving a narrow mobile search input.

[AdminLayout.vue](../frontend/admin-dashboard/src/layouts/AdminLayout.vue):15–21, 107–109, 152–168 fixes sidebar/main offsets at 240px or 56px without viewport adaptation. The 390px admin smoke view retained the 240px sidebar; content forced a layout viewport of about **1012px** and was cropped in the device screenshot. [SlideOverPanel.vue](../frontend/admin-dashboard/src/components/SlideOverPanel.vue):12–18 fixes drawer width at 640px. Checkout has a useful single-column breakpoint at [CheckoutView.vue](../frontend/customer-website/src/views/CheckoutView.vue):112–116, but the complete stepper/forms were not visually validated here.

**Action:** Set explicit narrow/mobile/tablet layouts: product cards with usable minimum widths, a second-row/full-width mobile search, a filter drawer, admin off-canvas navigation and viewport-bounded detail drawers. Keep horizontal scrolling inside genuinely wide tables, with key order actions visible. Start with 360/390/768/1024/1440px acceptance fixtures rather than arbitrary device names.

### F04 — P1: Add to Cart is invisible on keyboard focus and has nested interaction behavior

**Browser + source:** [ProductCard.vue](../frontend/customer-website/src/components/product/ProductCard.vue):69–107 wraps a button inside a focusable `role="link"` article with a bubbling Enter handler; lines 176–188 show the CTA only on `:hover`. Focusing the real button left its overlay opacity at **0**. Dispatching a bubbling Enter keydown at that focused button navigated to `/products/1`, confirming the parent handler fires; this was a synthetic DOM event check, not a complete trusted-key purchase test.

**Action:** Use a native product link plus a separate cart button, make touch CTAs visible, reveal on `:focus-within` where appropriate, and test Tab/Enter/Space without navigation from the cart action. Keep out-of-stock state and duplicate-submit guards.

### F05 — P1: missing icons and admin accessibility defects affect essential navigation

**Browser + source:** Both apps use `pi pi-*` classes, but neither manifest declares `primeicons` nor either active CSS entry imports its stylesheet. Admin [AdminLayout.vue](../frontend/admin-dashboard/src/layouts/AdminLayout.vue):61–92 and 97–103 relies on these icons. In the compiled admin page `::before` content for `.pi-home`, `.pi-shopping-cart`, `.pi-box` was `none`; screenshots showed absent glyphs. After collapse, all **seven navigation links** had empty text and no `aria-label` because visible labels use `v-if`.

[admin-dashboard/index.html](../frontend/admin-dashboard/index.html):2 has `lang=""`, and line 7 retains `Vite App`. Axe on fixture-backed `/admin/orders` reported **`html-has-lang`** (one node) and **`color-contrast`** (three nodes; including sidebar section labels styled gray-400 at [AdminLayout.vue](../frontend/admin-dashboard/src/layouts/AdminLayout.vue):194–201). The admin app has no skip link comparable to the customer one. These observations were not detected by the passing lint suite.

**Action:** Choose one existing-compatible icon strategy (explicit PrimeIcons dependency/style, or SVG replacements), give collapsed links persistent accessible names, fix page language/title and measured contrast, add an admin skip link, and verify drawers/menu focus behavior. This does not require a new component framework.

### F06 — P1: admin Products silently operates on only the first 100 products

**Source:** [ProductsPage.vue](../frontend/admin-dashboard/src/views/ProductsPage.vue):39–46 calls `listProducts(0, 100)` once and keeps only `response.data`. Lines 197–207 configure a client-side paginator/filter/sort without server paging or total count. Therefore products beyond the first 100 cannot be found through that table, and sort/filter describe only the loaded subset. Initial fetch failure also lacks a catch/error view and can resemble an empty catalog.

**Action:** Implement server pagination with total count and explicit error/retry state; agree server search/sort support before advertising global filtering. Validate with at least 150 products, including an item beyond the first page. Existing backend pagination can be reused; global search/sort may need backend contract work.

### F07 — P1: admin Orders page controls do not match server requests

**Source:** [OrdersPage.vue](../frontend/admin-dashboard/src/views/OrdersPage.vue):76–78 ignores `event.rows`; lines 154–166 enable lazy pagination with 10/25/50/100 options but no bound `first` and no sort handler. [useOrderAdminStore.ts](../frontend/admin-dashboard/src/stores/useOrderAdminStore.ts):18–29 retains page size 25. [orderAdminApi.ts](../frontend/admin-dashboard/src/api/orderAdminApi.ts):50–64 sends page/size/statuses only. Selecting another row count consequently still requests 25 rows. Sortable headings cannot establish server-wide ordering in lazy mode, and filter-reset pagination is not synchronized through `first`.

**Action:** Bind page, size, first offset, filter and supported sorting consistently. Remove unsupported sort affordances until the backend contract exists. Regression checks must assert actual requests and returned record ranges, including changing size on page 2 and filtering back to page 1.

### F08 — P1: optimistic cart rollback does not restore an existing item's prior quantity

**Source + independently reproduced probe:** [useCartStore.ts](../frontend/customer-website/src/stores/useCartStore.ts):28–48 uses `previousItems = [...items.value]`, then mutates an existing item's quantity/subtotal. The snapshot contains the same item object, so assigning it back after rejection retains the optimistic mutation. By comparison, quantity update at lines 53–64 copies each item. The same shallow-snapshot add implementation remains in the original checkout diff.

**Action:** Restore an immutable per-operation snapshot and define how overlapping cart mutations reconcile with server responses. First regression: existing quantity 1 → add 1 → rejected API response must show quantity 1 and the previous subtotal. The independent probe using the actual Pinia store and a mocked rejection observed quantity **2**, subtotal **20**, instead of quantity **1**, subtotal **10**. The existing rollback unit test adds a different product, so it does not exercise this shared-object case. Also check rapid requests and late responses. This reproduction establishes local store behavior, not live backend/cart behavior.

**Latest local status:** Snapshot aliasing is fixed in `useCartStore.addItem`, with permanent existing-item rejection and authoritative-server-response regressions. General overlapping cart mutation/full-response ordering remains unresolved; the [self-review](../_bmad-output/test-artifacts/frontend-regression-fixes-2026-11-09/README.md#self-review-and-remaining-boundaries) documents a concrete remaining interleaving. This is a tested local correction, not an integrated checkout release or a claim that all cart concurrency is solved.

### F09 — P1: dependency advisories need a reviewed remediation track

**Audit:** `npm ci` and `npm audit --json` reported **39 affected package entries: 19 moderate, 19 high, 1 critical**. `npm audit --omit=dev --json` reported **8 high** entries. These are package/graph counts, not 39 unique exploits. Lockfile evidence: Axios around line 3331, shell-quote 7101, Vite 7889, Vitest 8115, Vue 8212.

| Finding | Scope and interpretation | Proposed disposition |
| --- | --- | --- |
| `shell-quote@1.9.0`, critical audit entry | `npm explain shell-quote` resolves through dev `npm-run-all2`; relevant to tooling inputs, not shipped browser command execution | Review upstream patch/lock update and build-script behavior |
| `axios@1.18.1`, high aggregate audit entry | Contains browser clients; individual advisories include prototype-pollution gadgets as well as Node-only adapters | Patch deliberately; do not label all adapter issues reachable in this SPA |
| `vite@8.1.0`, high aggregate audit entry | Maintainer file-disclosure advisory requires specific dev-server exposure/import conditions; production static output is a separate surface | Update tools and verify plugins/build; do not expose an unpatched dev server as a production server |
| `vitest@4.1.9` / `@vitest/mocker` | Test/development surface; installed suites are jsdom, not a demonstrated public browser mock server | Patch with matching Vitest internals and rerun suites |
| `@vue/server-renderer@3.5.39` and propagated `vue` entry | SSR attribute-name advisory; these apps use `createApp` client rendering, no SSR entry was found | Track patch provenance; no SSR exploit demonstrated here |

The eight production-graph entries are `@vue/server-renderer`, `vue`, `axios`, `follow-redirects`, `nanoid`, `postcss`, `source-map-js`, `vite`. `npm ls vite --omit=dev` shows Vite also reachable through Vue Router's tooling/peer graph. **An npm production graph is not the same as executable code shipped in a browser bundle.** Investigate reachability and update the lockfile rather than ignoring the graph or treating every entry as an active browser vulnerability.

Verified official guidance: Axios identifies `>=1.20.0` as patched for the cited fetch gadget and has a [1.20.0 release](https://github.com/axios/axios/releases/tag/v1.20.0); [the advisory](https://github.com/axios/axios/security/advisories/GHSA-vh66-26gq-q6x8) requires its stated preconditions. Vitest's [manual mock advisory](https://github.com/vitest-dev/vitest/security/advisories/GHSA-qxg6-89mg-rqv8) identifies `4.1.12`; the [release exists](https://github.com/vitest-dev/vitest/releases/tag/v4.1.12). Vite's [file access advisory](https://github.com/vitejs/vite/security/advisories/GHSA-rq7h-c2jc-7f22) identifies `8.1.6` as a fix on the 8.1 line, with [release notes](https://github.com/vitejs/vite/releases/tag/v8.1.6). These are **verified minimum candidates for those advisories**, not claims of latest versions or a complete security clearance.

The Vue [maintainer advisory](https://github.com/vuejs/core/security/advisories/GHSA-g2v6-rqmx-r4w6) still lists no patched version while npm's range suggests versions below 3.5.42. Resolve that discrepancy against a maintainer-confirmed release before calling the issue fixed. Do not run `npm audit fix --force`: audit suggestions include major/downgrade resolutions for lint packages and require peer/toolchain review.

### F10 — P1: browser and accessibility CI do not gate the complete experience

**Source:** [.github/workflows/ci-frontend.yml](../.github/workflows/ci-frontend.yml):100–106 audits only the customer root with no prepared backend/data/auth, suppresses failures with `|| echo`, and starts an unpinned `npx serve`. Admin and protected checkout states are not audited. Lines 64–71 invoke lint scripts containing `--fix`; CI can modify files before later checks without rejecting the diff. Vitest configs have no coverage provider/threshold; CI does not request coverage. Local test counts therefore do not establish a line/branch percentage.

Baseline has no browser workflow or `test:e2e` script. Original checkout **already adds** a Playwright suite and browser CI; extend/review that effort. It is Chromium desktop only and purchase-recovery focused, so mobile/keyboard/admin still need coverage. Its workflow triggers on PR/manual events, not main pushes. Existing frontend CD resolves successful `Frontend CI`; the draft browser workflow alone does not prove browser results gate deployment or branch protection.

**Action:** Read-only CI lint, explicit `--run`, pinned browser/server tooling, deterministic fixture states, targeted browser tests and axe assertions on both apps. Gate critical regressions through actual branch/release policy and test deliberate failures. Add measured coverage for high-risk stores/API contracts instead of inventing a baseline percentage or imposing a blanket target without data.

### F11 — P2: search can show stale or misleading results

**Source + independently reproduced probes:** [useProductStore.ts](../frontend/customer-website/src/stores/useProductStore.ts):31–80 assigns any completed response; no cancellation or request generation fences prevent an older query overwriting newer results. [SearchBar.vue](../frontend/customer-website/src/components/product/SearchBar.vue):12–22 has the same autocomplete race. Debounce reduces volume but does not order responses. [SearchResultsView.vue](../frontend/customer-website/src/views/SearchResultsView.vue):101–133 has no error branch, so an initial fetch failure can display “No results found.” [FilterSidebar.vue](../frontend/customer-website/src/components/product/FilterSidebar.vue):22–25 renders multiple brand checkboxes but sends only the first selected brand.

The review also found a separate filter-clearing defect: `SearchResultsView.vue`:34–40 merges the prior `productStore.filters` into the next request; `onFilterChange` at lines 55–57 passes only currently active fields. When the sidebar omits a cleared brand, minimum price or rating, the previous value survives the merge. Explicit tag removal and Clear All follow different paths and do not demonstrate that unchecking/resetting individual controls works. A shallow-mounted actual `SearchResultsView` emitted `{ brand: 'Fixture' }`, then `{}` from its sidebar; the next mocked API request still contained `brand: 'Fixture'`. A separate actual-store probe completed a newer search before the older search and observed the older result replacing it while `filters.keyword` still identified the newer query.

**Action:** Fence/cancel obsolete reads, replace the sidebar's complete filter snapshot rather than merging omitted fields into stale state, separate error/empty/loading state, synchronize filters to the URL, and either support multiple brands in the API or present a single-choice control. Test intentionally reordered responses, select → uncheck of each filter, and 500/timeout separately from a successful empty result. Seeded category IDs in `useCategoryStore.ts`:9–15 are an additional integration assumption to validate against a public category endpoint; they are not proof of current data mismatch.

**Latest local status:** Search response fencing and complete filter snapshots are fixed locally with deferred-promise store tests and actual-view/sidebar/API regressions. Keyword navigation clears controls, and active filters now have real named removal buttons whose DOM-click tests verify that later sidebar input cannot revive a removed field. The final targeted suite passes 47 tests; the same tests fail against baseline source. Autocomplete ordering, error/empty presentation, multi-brand support, category integration and URL persistence are outside this narrow fix and remain open. See the [implementation/validation report](../_bmad-output/test-artifacts/frontend-regression-fixes-2026-11-09/README.md); this is not an integrated release claim.

### F12 — P2: shared styling contracts are duplicated and a dependency is implicit

**Source:** Shared tokens and Aura presets are exported by [shared/src/index.ts](../frontend/shared/src/index.ts):1–13. Palette/spacing/shadows are also handwritten in both app `assets/app.css` files (customer lines 8–59, admin 7–60), plus numerous scoped literals. Admin density intentionally differs from customer density; unifying everything would discard a useful distinction. Customer imports `@robo-mart/shared` in `main.ts`:8 and multiple views but omits it from [customer package.json](../frontend/customer-website/package.json); admin declares it. Root workspace linking currently makes customer builds succeed, masking the undeclared consumer relationship.

**Action:** Declare both consumers, centralize semantic colors/focus/error tokens and emitted CSS variables, keep separate customer/admin density aliases. Migrate the deprecated theme import in an isolated PR. Grow shared patterns around form-field errors, request states, money/status presentation and bounded drawers where both consumers actually benefit; avoid extracting feature stores or introducing a generic page framework.

### F13 — P2: error normalization and maintenance state undermine recoverable UX

**Source:** Customer [client.ts](../frontend/customer-website/src/api/client.ts):143–166 replaces 503/404/500/network errors with plain `Error`, discarding status/body/trace context for callers. Checkout interprets Axios error codes at `useCheckoutStore.ts`:79–99. Any no-response request sets maintenance; [useUiStore.ts](../frontend/customer-website/src/stores/useUiStore.ts):10–15 prevents recovery from maintenance; [DegradationBanner.vue](../frontend/customer-website/src/components/common/DegradationBanner.vue):39–54 overlays the site without a retry action. One transient request failure is insufficient evidence of planned maintenance.

**Action:** Define a small typed API error preserving HTTP status, application code, trace ID and ambiguous-write semantics. Use operation-level retry and a reconnect action; only show maintenance when an explicit service signal supports it. Preserve the original checkout's special handling for ambiguous order/acknowledgement requests when changing the client. Do not automatically retry all mutations.

### F14 — P2: admin authentication/session recovery and WebSocket reconnect need tests

**Source:** Admin guard awaits initialization, which avoids the customer startup race. However, [AuthCallbackView.vue](../frontend/admin-dashboard/src/views/AuthCallbackView.vue):9–16 always returns to dashboard; the original protected route is not retained. [adminClient.ts](../frontend/admin-dashboard/src/api/adminClient.ts):22–28 redirects immediately on 401, unlike the customer's coordinated refresh. [useAdminAuthStore.ts](../frontend/admin-dashboard/src/stores/useAdminAuthStore.ts):71–102 handles loaded/unloaded events but lacks a silent-renew-error handler. [auth/keycloak.ts](../frontend/admin-dashboard/src/auth/keycloak.ts):13 configures `/silent-renew.html`, absent from admin public assets; this affects iframe fallback if needed, not proof that normal refresh-token renewal fails.

[websocketClient.ts](../frontend/admin-dashboard/src/api/websocketClient.ts):4–11 captures the initial token in CONNECT headers; [useWebSocket.ts](../frontend/admin-dashboard/src/composables/useWebSocket.ts):68–112 does not update it on renewal/reconnect. A reconnect after expiry can therefore reuse an old token. Actual expiry/reconnect was not exercised here.

**Action:** Preserve admin deep links, handle renewal failure explicitly, rebuild/update CONNECT authentication before reconnect and test logout cleanup. Reuse OIDC client primitives; avoid duplicating low-level JWT handling. Client-decoded roles are for UI behavior; gateway JWT/RBAC enforcement must remain authoritative.

### F15 — P2: checkout form accessibility and payment/locale expectations need explicit boundaries

**Source + opportunity:** [StepShippingAddress.vue](../frontend/customer-website/src/components/checkout/StepShippingAddress.vue):12–33 assumes US postal format and default country while exposing country as free text. [StepPayment.vue](../frontend/customer-website/src/components/checkout/StepPayment.vue):100–183 visibly labels the flow as a demo and collects a mock card/CVV; the order request contains items/address, not those card fields. This is a demo interaction, not an implemented payment-provider integration. Do not claim that changing the form enables real payments.

Shipping/payment fields have labels and visible errors, but lack consistent `autocomplete`, numeric `inputmode`, error IDs/`aria-describedby` and explicit invalid state. [SagaProgressOverlay.vue](../frontend/customer-website/src/components/checkout/SagaProgressOverlay.vue):9–25 announces status but does not manage background focus; cycling text comes from a timer, not backend stage events. Screen-reader/focus testing is still required.

**Action:** Add input semantics, error summaries/focus handling and honest pending/retry copy within the existing flow. Keep card details out of any persisted checkout recovery record. Choose English/USD/US assumptions versus Vietnamese/VND/local address rules as a product decision; Vietnamese conversation language does not establish a requirement to translate the application. Real payments require separate provider/backend work.

### F16 — P2/P3: performance and broader visual redesign are measurable opportunities

**Measured build evidence:** Customer emitted entry chunk is **457.21 kB raw / 101.08 kB gzip**, CSS **26.02 / 5.81 kB**; admin entry is **399.59 / 91.04 kB**, CSS **17.99 / 4.67 kB**. Customer checkout route chunk is **105.26 / 31.62 kB**; admin Chart.js `auto` chunk is **203.08 / 69.61 kB** and shared table/column chunk **351.50 / 73.95 kB**. These are individual build artifacts, **not total route payloads**. Entry HTML preloads additional shared chunks. No Core Web Vitals or real network latency were measured.

**Action:** Establish route-level transferred-JS/image budgets and mobile traces before manual chunking. Inspect initial font requests and image variants (`srcset`/sizes), prioritize the actual LCP image rather than lazy-loading every above-fold card, and retain existing route splits. Verify Chart.js stays deferred to reporting. Remove unused scaffold components only when confirmed unused. Vue's [performance guidance](https://vuejs.org/guide/best-practices/performance.html) supports measurement, bundle review and lazy routes; it does not establish that this application needs SSR.

SSR/SSG for catalog SEO, richer merchandising, saved addresses, a one-page checkout, dark mode and a component documentation site are **P3 options**. Their value requires product requirements or analytics. They are not prerequisites for repairing the observed flows.

## Reconciliation with work already in progress

The original checkout changed concurrently during this assessment. The later read-only `git diff --stat -- frontend .github/workflows` snapshot contained **27 tracked files, 501 insertions and 79 deletions**; additional untracked files include the recovery helper, order-polling composable, Playwright fixture/spec/config and browser workflow. Backend cart/order/payment/Saga changes also exist. This is an observed working-copy snapshot, not a frozen release. Those files were inspected **without running installs/tests or modifying that checkout**.

| Baseline gap | ORIGINAL implementation observed | Treatment in an upgrade |
| --- | --- | --- |
| Missing checkout key/payload persistence | `frontend/customer-website/src/stores/checkoutAttempt.ts`:5–38 stores a per-user attempt in sessionStorage, intentionally excluding card/CVV; `useCheckoutStore.ts`:108–140 freezes/reuses payload and sends the key; `api/orderApi.ts`:19–29 sets header | Reuse and review; cross-tab/browser-close persistence is a separate requirement |
| Server cart returns after purchase | `useCheckoutStore.ts`:68–92 acknowledges matching accepted order snapshots; `useCartStore.ts`:109–112 and `api/cartApi.ts` add completion; cart line identities/backend atomic processing are part of the same change | Treat as a coordinated backend/frontend contract, not a front-end-only reset |
| Cleanup failure misreported as failed purchase | `useCheckoutStore.ts`:79–92 retains receipt when acknowledgement fails; confirmation retries reconciliation | Preserve accepted-order behavior and distinct cleanup messaging |
| Auth restoration/deep links | `main.ts`:44–50 initializes auth before router install and waits for readiness; `router/index.ts`:65–73 passes protected `fullPath` into login | Adopt together; keep fresh-login/reload/query/fragment tests |
| Hidden generic checkout failure | `components/checkout/StepConfirm.vue`:20–25 shows error/pending-attempt feedback and offers “Check Order” | Reuse; further visual refinements must preserve recovery semantics |
| No browser suite | `e2e/checkout.spec.ts`:3,42,85,118,149 defines five real-stack recovery scenarios; `playwright.config.ts`:26 limits projects to desktop Chromium; draft `.github/workflows/ci-browser-e2e.yml` builds isolated backend/Keycloak stack | Review then extend with mobile, keyboard, a11y and admin scenarios; do not start a parallel E2E framework |
| Eventual order/cancellation feedback under Saga extraction | New `customer-website/src/composables/useOrderPolling.ts`:5–33 schedules bounded background refreshes; customer confirmation/detail/list, `shared/src/components/OrderStateMachine.vue`, admin Orders/Dashboard/ReportingPanel add `CANCELLATION_PENDING` and `REFUND_FAILED` handling | Reconcile these evolving status contracts rather than proposing a separate poller or duplicate status mapping; check all terminal/pending cases with the final backend |

The existing `checkout-browser-recovery-2026-11-05.json` records prior local passes, source base `4ec50bb`, **uncommitted implementation**, and historical branch `codex/checkout-browser-regressions`. Current original branch is `codex/extract-checkout-saga`. That artifact is useful historical evidence, **not a verification of the current larger dirty checkout or a deployed release**. This assessment did not rerun those five full-stack scenarios. Their recorded limitations include per-tab session lifetime and no general demo rollout assertion.

The first read had no admin frontend changes; a later recheck found the status-display additions described above, which were also inspected. They do not change the assessed shell, icons, product pagination, order page-size wiring or theme tokens. The mobile/icon/table/shared-token issues and shallow cart add rollback remain separate proposed work. Because the original checkout is mutable, capture its final reviewed commit and rerun integration/browser checks before selecting changes for release. No success claim from this baseline is transferred to the newer polling/status/Saga work.

## Upgrade choices and compatibility tradeoffs

| Option | Benefit | Cost/risk | Recommendation |
| --- | --- | --- | --- |
| A. Repair interactions and recovery on current architecture | Fastest route to reliable purchases/admin operations, smallest regression surface | Needs coordination with existing backend recovery changes | **MVP foundation** |
| B. A + token/theme consolidation and selected shared patterns | More consistent UI and cheaper future changes | CSS cascade/density and PrimeVue theme regressions across both apps | Do incrementally after critical flow fixes |
| C. Newer PrimeVue major plus visual refresh | Access to vendor enhancements and a future maintenance path | Theme/cascade, component rendering, sizing, and vendor license-model changes require review | Separate compatibility spike; do not bundle with checkout recovery |
| D. Replace component stack/framework or add SSR/SSG | Could serve a validated design/SEO requirement | Rebuilds tables/forms/auth/hydration/test contracts; highest effort | Defer pending a concrete unmet requirement |

Official documents checked on 2026-11-09:

- **PrimeVue/themes:** The vendor [theme migration issue](https://github.com/primefaces/primevue/issues/7152) directs consumers from `@primevue/themes` to `@primeuix/themes`. Baseline already resolves PrimeUIX themes 2.0.3 through the deprecated alias. First evaluate direct imports/declarations compatible with that existing major, shared peer metadata, and both Aura presets. A package rename need not be a simultaneous theme redesign or jump to the newest PrimeUIX major.
- **PrimeVue major option:** The official [v5 migration guide](https://primevue.dev/migration/v5/) describes an API compatibility goal, a changed Community/Commercial model, deprecations and a 16px sizing assumption with a 14px compatibility preset. These are reasons for a separate evaluation; this report does not prescribe v5 or assert it is the newest release. Review local `.p-*` CSS overrides, drawers, stepper, DataTable, form messages and screenshot baselines against the selected release.
- **Vite:** The official [v8 migration guide](https://vite.dev/guide/migration.html) documents Rolldown/Oxc and changed browser targets. Baseline is already Vite 8; avoid introducing old Rollup/esbuild configuration recipes blindly. A security patch should be tested with Vue/Tailwind/devtools plugins and the SockJS `globalThis` define.
- **Vue Router:** Its official [v5 migration guide](https://router.vuejs.org/guide/migration/v4-to-v5) explains the merged optional file-routing tooling. Baseline already uses v5 with hand-written routes. No need to adopt file routing, experimental loaders or another data cache to repair its startup guard.
- **Tailwind:** The official [compatibility guide](https://tailwindcss.com/docs/compatibility) specifies Chrome 111+, Safari 16.4+ and Firefox 128+ for v4. Vite transpilation alone cannot supply unsupported CSS features. Agree the actual support matrix, then exercise Safari/WebKit and Firefox as well as Chromium. The project already uses the recommended Vite plugin; a v3-to-v4 migration is not pending.

Keep Vue/Pinia/TypeScript/OIDC major changes out of the initial UX batch. For every selected dependency update, record exact versions and primary release notes in that implementation PR, review peer/Node requirements, update the root lockfile once, and run both consumers. No dependency versions were changed or candidate migrations tested during this assessment.

## Proposed MVP and phased roadmap

Effort is relative: **S** ≈ 1–2 focused engineering days, **M** ≈ 3–5, **L** ≈ 1–2 engineer-weeks. These are planning ranges including focused tests/review, not delivery commitments; backend availability and existing work integration can dominate. Work packages overlap and should not be summed as an exact schedule.

**MVP outcome:** A shopper can discover products on a phone, add/edit cart with keyboard or touch, authenticate without losing destination, place/recover a single order, and receive honest pending/failure feedback. An administrator can find products beyond the first 100, page orders accurately, and navigate/read/edit relevant records with named controls and bounded panels. Existing English/USD/demo-payment behavior remains the planning assumption until product direction changes.

| Phase | Deliverable and findings | Effort | Dependencies | Acceptance checks |
| --- | --- | --- | --- | --- |
| 0. Establish integrated release baseline | Review/select original recovery changes, backend acknowledgement contract and E2E CI; identify final commit; triage advisories; align Node support (F01/F02/F09/F10) | M, plus review of existing backend work | Recovery/Saga owner, isolated stack, branch/release policy | Real Keycloak + backend: lost order response/reload uses same key and order; cleanup failure retains accepted order; concurrent cart edits survive; deep links retain path/query/fragment. Exact commit recorded, no accidental dependence on dirty files |
| 1. Customer purchase usability | Fix product card interaction, narrow grids/header/filter layout, cart rollback, typed error/retry behavior, accessible form errors (F03/F04/F08/F11/F13/F15) | L | Phase 0 recovery invariants; agreed mobile targets and API filter scope | 360/390/768/1024/1440px screenshots; keyboard discovery→cart→checkout; visible focus and CTA; rejected cart add restores prior quantity; out-of-order search does not replace latest query; clearing filters removes their API parameters; error differs from zero results; ambiguous POST does not create a new attempt |
| 2. Admin operational MVP | Icons/named collapsed nav, lang/contrast/skip link, bounded drawers, server catalog paging, accurate order controls, auth/reconnect checks (F03/F05/F06/F07/F14) | L | Backend catalog search/sort decisions; admin fixture data/roles | Find product 101+; fetch failures show Retry; request `size` equals selected rows; filter resets first page; sort matches server or is absent; non-admin cannot access protected operations; expiry/reconnect and logout work; primary actions remain usable at narrow width |
| 3. Shared and enforced quality | Direct maintained theme imports, declared consumers, semantic token source and selected field/request-state patterns; read-only CI and targeted a11y/browser gates (F09/F10/F12) | M–L | Stable flow examples from phases 1–2; chosen compatible theme version | Both apps type-check/unit/build; no unexplained theme screenshot drift; CI fails deliberate serious axe and recovery regressions; shared changes trigger both apps; each advisory fixed or documented with reachability and owner |
| 4. Measured optimization and optional visual investment | Route/network budgets, LCP image and font review; broader brand/merchandising or v5/SSR spike only if justified (F16) | M for measurement/targeted fixes; larger options need new estimates | Representative data/network/device traces, product priorities | Reproducible before/after route traces and payload totals; no regression in purchase/admin checks; architecture decision records evidence and ongoing costs |

Phase 0 is a dependency for releasing checkout changes, not a request to wait before fixing independent CSS/icons or analyzing packages. Security triage and targeted patch PRs can proceed alongside interaction work. Avoid combining a large theme/major migration with the recovery integration: keeping each PR reviewable makes rollback and diagnosis practical.

### MVP acceptance suite

Use the original Playwright suite as the real-stack base and add focused fixture browser checks where deterministic UI/error cases are more practical:

1. **Buying:** browse/search → detail → add/update/remove → guest merge/login → checkout → confirmed/pending/cancelled; cover 401 refresh, stock rejection, ambiguous timeout and rejected cart add. Confirm server-authoritative totals/status and absence of persisted card/CVV.
2. **Recovery:** committed response loss, reload/retry identity, server cart completion once, newer cart edits, acknowledgement loss, protected deep link after fresh login. Preserve all five existing original scenarios.
3. **Admin:** paginated catalog with >100 entries, server error/retry, order size/filter/sort contract, allowed fulfillment transitions, narrow detail drawer, named collapsed navigation, renewal/reconnect/logout.
4. **Accessibility/responsive:** automated axe on meaningful loaded and error states in both apps; keyboard focus order, Enter/Space, skip links, modal focus restoration, form error announcements, reduced motion and 200% zoom. Include manual screen-reader checks; zero axe findings alone is insufficient.
5. **Quality:** both app type-check/unit/build, read-only lint/format, lockfile/advisory review and stable browser checks on the selected source commit. Establish measured coverage and route budgets before enforcing agreed thresholds. Do not claim an arbitrary percentage or performance score from this assessment.

## Validation executed and limitations

All npm commands below ran in this **assessment worktree**, from `frontend/` unless another directory is specified. Exit results for wrappers were inspected along with their logs.

| Command/check | Actual result |
| --- | --- |
| `orca skills get orca-cli --json`; `orca worktree current --json` | Passed; confirmed CLI-created independent worktree and origin/main base |
| `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git log -1` in baseline; corresponding original status/HEAD and scoped `git diff` reads | Baseline initially clean; original dirty, same HEAD; changes distinguished above |
| `node --version`; `npm --version` | `v24.21.0`; `11.19.0` |
| `npm ci` | Passed; 575 packages added, 579 audited; deprecated-theme/glob and install-script policy notices; 39 vulnerability entries |
| `npm run type-check --workspaces --if-present` | Passed for both apps; shared has no matching script |
| `npm run test:unit --workspaces --if-present -- --run` | Passed: customer **27 files / 240 tests**, admin **19 files / 89 tests**; 329 total |
| `npm run build --workspaces --if-present` | Passed for both apps, including their configured type checking; bundle metrics above |
| `../node_modules/.bin/oxlint .` and `../node_modules/.bin/eslint .`, each run with cwd set to each app | Passed for both; deliberately omitted `--fix` and cache flags |
| `../node_modules/.bin/prettier --check src/`, cwd each app | Passed for both; equivalent checked scope to CI |
| `npm audit --json`; `npm audit --omit=dev --json` | Findings: 39 total and 8 high production-graph entries respectively; not a clean security result |
| `npm explain shell-quote`; `npm explain @vue/server-renderer`; `npm ls vite --omit=dev` | Completed; used to qualify tool/SSR/production-graph paths rather than infer shipped reachability |
| `npm run preview --workspace=customer-website -- --host 127.0.0.1 --port 15173 --strictPort`; admin equivalent on 15174 | Both served the existing production builds; isolated from default dev ports |
| `node /tmp/robomart-assessment-browser.cjs`, cwd frontend | Final run exited 0, Chrome **156.0.8078.80**, direct CDP, private profile, synthetic API/OIDC. Desktop/mobile layout, card focus/key bubbling, collapsed link names and customer startup deep link observations recorded above; screenshots visually inspected |
| Axe via installed `axe-core` injected into isolated Chrome, WCAG 2 A/AA and 2.1 AA tags | Customer fixture home at 390px: zero violations in that state. Admin Orders desktop: `color-contrast` at three nodes, `html-has-lang` at one. This is a detected-defect result, not an accessibility pass for both apps |

The independent review retained the original build/unit logs, audit JSON, browser harness/output and both mobile screenshots in the [review evidence directory](../_bmad-output/test-artifacts/frontend-upgrade-review-2026-11-09/README.md), alongside new probe and control-run evidence. Original `/tmp/robomart-assessment-*` files remain temporary; archived `initial-*` files identify evidence from the original assessment, not new browser/build/audit executions by the reviewer. The browser harness initially failed on a cancelled CDP interception, then on temporary-profile cleanup after collecting observations; its final rerun handled cancellation/cleanup and completed. No application source was changed to make checks pass.

### Independent review executed

The reviewing agent inspected the cited source/configuration, raw evidence, lockfile versions/engines, current original-checkout implementations for overlapping work, and the two mobile screenshots. The maintainer advisory/release links and migration guidance were reopened; no dependency candidate was installed or tested. The read-only original-checkout snapshot is identified by content hashes in `review-provenance.json`; it remains an unverified, changing working copy.

| Review check | Result and interpretation |
| --- | --- |
| `npm run test:unit --workspace=customer-website -- --run src/stores/__tests__/frontendAssessmentReview.spec.ts` | **Exit 1, three behavioral assertions failed**, reproducing F08 rollback, F11 stale response and F11 cleared-filter retention. Mocks control API rejection/response order; actual stores and the actual search view execute. This is defect evidence, not a passing suite. Exact probe text, log and exit metadata are archived. |
| Temporary probe removal | Removed in a `finally` block; only its `.ts.txt` evidence copy remains, outside application test discovery. No product/test source change is retained. |
| `npm run test:unit --workspaces --if-present -- --run` | **Exit 0: 240 customer + 89 admin tests passed** after probe removal. Confirms the existing tests miss the reproduced behaviors. |
| `npm run type-check --workspaces --if-present` | **Exit 0**, both apps. |
| Build/lint/format/browser/audit | Reviewed original execution evidence; **not rerun** during this independent review. No new live-stack/E2E, deployed-state or migration-compatibility claim is made. |

Not executed / limits:

- No current original-checkout install, unit/build, or real-stack E2E rerun. Its dated five-scenario report remains historical evidence only.
- No backend services/Compose stack started, no real checkout/login/admin write performed, no deployment or production behavior verified. Browser API/OIDC fixtures establish UI rendering/interaction only; they do not validate gateway authorization or payment semantics.
- No Safari/WebKit/Firefox, physical mobile/touch device, full keyboard journey, screen reader or entire checkout visual audit. Emulated device width and synthetic Enter event do not substitute for these.
- No coverage instrumentation, Lighthouse/Core Web Vitals, load test, image-network profile or bundle reachability exploit investigation. Build timings on this host are not user-perceived performance measurements.
- No dependency upgrade/migration candidates installed or validated. Official advisories/docs were checked, but future implementation must verify the exact chosen release and complete dependency graph again.
- Full GitHub Actions/release-policy runner checks were not reproduced; `kustomize` was not available on PATH. Frontend CI's release-policy test step was inspected, not reported as locally passing. Actual branch protection, remote required checks and deployed versions were not queried.
- Local Node 24 success does not establish Node 22 CI parity. No CI failure is inferred from that difference alone.

## Decisions requested for implementation

1. **Approve the focused MVP (recommended) or a broader visual redesign.** MVP covers purchase reliability/mobile/accessibility and admin data/navigation correctness, preserving the current stack.
2. **Set supported customer/admin devices and browsers.** Recommended starting point: customer fully usable on phones; admin responsive for navigation/order triage and bounded tables, with dense bulk editing optimized for desktop. Confirm whether full mobile admin editing is mandatory and whether older-than-Tailwind-v4 browsers matter.
3. **Confirm market/payment scope.** Retain English/USD/US address/demo payment for the first release, or explicitly fund Vietnamese/local address/currency and real-payment requirements as separate work.
4. **Select the reviewed integration commit for checkout recovery/Saga work.** Original dirty changes must be reconciled by their owner and validated as one release; no competing implementation is proposed here.
5. **Keep theme maintenance separate from a PrimeVue major decision.** Recommended: resolve the deprecated theme alias and compatible patch updates first; evaluate a newer major only with visual regression evidence and the vendor's published distribution/license model understood.


## Latest local UI status (2026-11-09)

This section supplements the assessment rather than replacing its baseline evidence. The implementation remains in `tuanpm2026/codex-frontend-upgrade-assessment` at HEAD `4ec50bbaa04933c43a37066f88c293e056c24012` plus uncommitted changes. The UI dispatch started from the earlier F08/F11 fixes and 356 passing tests; its snapshot/hashes preserve that distinction. The original checkout was not modified or imported.

| Finding | Latest local disposition |
| --- | --- |
| F03 | Requested customer home/search/header/filter/cart/detail/checkout layouts implemented and browser-checked at 360/390/768/1024/1440 CSS pixels. Mobile filters use one persistent inline disclosure. Admin mobile navigation, viewport-bounded detail/product Drawers, contained table scrolling and initially visible sticky View/Edit actions implemented. Other admin screens and physical-device/browser coverage remain follow-up. |
| F04 | Native product link and separate always-visible cart action implemented; static display ratings have no interactive descendants. Browser Tab/Enter/Space/touch and pending duplicate-submit tests cover the interaction. |
| F05 | Both apps explicitly import pinned `primeicons@7.0.0`; missing icon alias corrected. Collapsed/mobile navigation names, dialog/toggle state, Escape/focus return, main skip link, admin language/title, measured contrast and visible button focus corrected. Final order View icon and focus contrast are 6.4129:1 against the measured row background. This is not a whole-app accessibility certification. |
| F08/F11 earlier fixes | Preserved locally with their 27 regressions and original red/green evidence. General cart concurrency, API facet semantics and search error handling remain follow-up. The UI batch additionally retains current-keyword brand choices through loading so a focused checkbox stays mounted, and returns focus when responsive collapse hides an active control; both have new red/green regression evidence. Keyword resets and the existing search request fence remain intact. |
| F12 | Customer now explicitly declares the already-consumed shared workspace, including the new media-query listener helper used by both apps. Token/theme duplication and broader shared design-system ownership remain unresolved. |
| F15 | Shipping/payment input hints, associated invalid/error descriptions and linked error-summary focus implemented. Stepper ownership/ID relationships, processing modal focus, honest pending/rejection copy and result-heading focus implemented. English/USD/US postcode/demo payment rules retained; no card/CVV persistence or backend protocol changes. Real payment/provider and screen-reader validation remain separate work. |

The only added dependency is the existing-compatible CSS icon artifact, pinned to a verified release; no framework/theme/security upgrade was bundled. Its compatibility/asset-cost tradeoff and official references are documented in the implementation report.

**Executed local validation:** `npm run test:unit --workspaces --if-present -- --run` passed **380 tests** (customer **286**, admin **94**), up **24** UI tests from the 356-test pre-UI state. Both workspace type checks/builds and read-only changed-file ESLint/Oxlint/Prettier checks passed. The full isolated Chrome matrix has **77 screen/width observations**, zero document overflow, zero runtime exceptions, **40 axe state audits with zero detected violations**, and verified native keyboard/touch/pointer interactions. A separate focused rerun checks filter focus preconditions; the 720×475 viewport is a 200% reflow equivalent for a 1440×950 viewport. These are fixture/Chromium/Node 24 results, not full-stack, Node 22 CI, multi-browser, screen-reader or overall WCAG conformance claims.

See the [UI implementation report](../_bmad-output/test-artifacts/frontend-uiux-upgrade-2026-11-09/README.md) for actual commands/exit receipts, baseline and final screenshots, metrics, contrast probes, failures corrected during self-review and remaining boundaries. The prior [regression-fix report](../_bmad-output/test-artifacts/frontend-regression-fixes-2026-11-09/README.md) and [independent review evidence](../_bmad-output/test-artifacts/frontend-upgrade-review-2026-11-09/README.md) are unchanged historical evidence.

**Roadmap effect:** the requested visual/interaction parts of customer usability and admin navigation/work-screen phases are locally complete. The MVP remains incomplete until recovery/auth integration, server pagination/order-control correctness, dependency advisory remediation and CI/release validation are handled. Do not label all F03–F15 findings or the entire roadmap resolved. Integration still requires selecting the reviewed recovery baseline and agreeing supported browsers/devices and mobile admin scope.
