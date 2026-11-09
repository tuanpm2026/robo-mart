# Frontend responsive and accessibility implementation — 2026-11-09

## Outcome and provenance

This is a **local implementation**, not an integrated or deployed release. Work was performed only in `/Users/tuanphung/orca/workspaces/robo-mart/codex-frontend-upgrade-assessment`, branch `tuanpm2026/codex-frontend-upgrade-assessment`, HEAD `4ec50bbaa04933c43a37066f88c293e056c24012`, on top of the preceding three local regression fixes. No commit, push, merge, deployment, backend operation or original-checkout modification was performed. No other agent was spawned.

The initial [assessment](../../../docs/frontend-upgrade-assessment-2026-11-09.md), applicable `AGENTS.md`, implementation/configuration/tests and relevant customer/admin story records were read. Work addresses the requested UI portions of **F03/F04/F05/F15**, with a narrow declared-consumer correction from **F12**. It preserves the earlier F08/F11 store fixes and all 27 prior regressions.

- [start-snapshot.json](start-snapshot.json) records the starting branch/commit/status, hashes of 177 source/manifest/document files, the root lockfile hash and **86 files** of prior review/fix evidence.
- [start-sources.json](start-sources.json) freezes the pre-UI text, including the earlier local fixes. [ui-dispatch.diff.txt](ui-dispatch.diff.txt) separates this UI delta from the prior dirty baseline; `git diff` alone includes both dispatches.
- The original checkout `/Users/tuanphung/work/robo-mart` was not used as an implementation source. Existing assessment observations about its recovery/Saga work remain historical and unintegrated.
- Final hashes, preserved-evidence comparison, source delta, build manifests and command receipts are indexed in `completion-manifest.json` and `validation-index.json`.

## Implemented changes

| Area | Actual behavior and affected files |
| --- | --- |
| Customer discovery | `DefaultLayout.vue` gives the narrow header separate logo/actions and full-width search rows. `HomeView.vue`/`SearchResultsView.vue` use explicit grid breakpoints, including a readable single column below 381px. `ProductDetailView.vue` stacks gallery/content, removes narrow sticky positioning, bounds long text, shortens the redundant current breadcrumb on mobile and supplies a real named Home router link. |
| Filters | One `FilterSidebar.vue` remains mounted as an **inline disclosure**, closed initially on mobile and expanded on desktop. Native toggle state/control IDs, named minimum/maximum price handles and actual arrow-key operation are present. Closing/reopening and resizing preserve complete filter snapshots. Tag removals while closed do not revive brand, price or rating. Focus returns to the toggle only if a breakpoint hides the currently focused content. |
| Filter request focus | `SearchResultsView.vue` retains the known brand choices for the current keyword while product results refresh, keeping a focused checkbox mounted. A new keyword clears those inferred choices. This does **not** retain stale product results, alter the request fence or provide server-wide facets. |
| Product cards | `ProductCard.vue` has a native product anchor and a separate, always-visible outlined cart button with a product-specific accessible name. Read-only ratings contain static stars and a numeric accessible description, with no hidden radios or other focusable descendants. Stock/loading/error and duplicate-submit guards remain. |
| Cart and checkout reflow | `CartView.vue`, `CartItem.vue` and checkout summary/confirm styles wrap long names and actions, stack narrow columns and keep quantity/subtotal/remove controls usable. Quantity labels now target the real `InputNumber` input through `inputId`. The four checkout steps fit the phone width. |
| Checkout semantics | The existing PrimeVue Stepper is retained with a named group/tablist, direct native tab children, explicit selection and valid tab/panel ID references. Successful Continue/Back transitions focus the active heading without taking focus from an invalid-submit error summary or an active processing dialog. |
| Shipping/payment | Existing schemas, US postcode rule, default country, English/USD and demo payment remain. Inputs gain autocomplete/inputmode, real error IDs, invalid state and descriptions. `FormErrorSummary.vue` focuses after an invalid submit and links to real controls. Error text is readable and field/action rows stack on phones. |
| Processing and results | `SagaProgressOverlay.vue` uses the existing PrimeVue Dialog for modal focus trapping and honest submission copy, not timer-derived business-stage claims. Escape cannot dismiss an in-flight order. After a rejection, focus prioritizes the visible error alert over the earlier heading. Unknown errors are visible; payment rejection asks users to check their order status before retrying. `OrderConfirmationView.vue` focuses its result heading and avoids confirmed/delivery promises for pending or compensating statuses. No order/cart/backend protocol was changed. |
| Admin navigation | `AdminNavigation.vue` reuses one navigation definition with persistent link names. `AdminLayout.vue` supplies mobile off-canvas navigation with named dialog/toggle, Escape/close/focus return, route-close behavior and desktop-resize focus fallback. Collapsed desktop links retain names; a skip link targets focusable main content. Long breadcrumb/account names cannot expand the header. |
| Admin work screens | Orders/Products tables scroll within named, focusable regions. Sticky right-side Actions keep View/Edit visible at `scrollLeft=0` on narrow widths. Headers/filters/actions wrap. Product load failures have a real Retry action. Inline price editing uses a native named button, focuses its actual input and returns focus on Escape/save. Product form fields have label/error associations and stack on narrow screens. |
| Drawers and icons | `SlideOverPanel.vue` is viewport-bounded, named, scrolls internally and restores an existing trigger after close. Order item tables scroll within their own region; long identifiers wrap. Both apps explicitly import pinned PrimeIcons CSS; the nonexistent `pi-user-circle` alias becomes `pi-user`. Admin HTML is `lang=en`, titled `RoboMart Admin`. Connection/upload text contrast and button focus outlines are corrected. |
| Shared contract | `shared/src/useMediaQuery.ts` owns the breakpoint listener lifecycle and is consumed by both apps. Customer now explicitly declares its existing `@robo-mart/shared` dependency. Existing tokens, themes, PrimeVue, Pinia and OIDC integration remain. |

## Dependency decision and measured cost

Only **`primeicons@7.0.0`** was added to the two application manifests and root lockfile, using `npm install --workspace=customer-website --workspace=admin-dashboard --save-exact primeicons@7.0.0 --ignore-scripts` (exit 0). No existing dependency version was upgraded. `npm view primeicons@7.0.0 version dist.integrity --json` confirmed the exact artifact; the lockfile pins its integrity.

The [official PrimeVue 4 icon guide](https://v4.primevue.org/icons/) documents the CSS/class strategy and import; the [PrimeIcons 7.0.0 release](https://github.com/primefaces/primeicons/releases/tag/7.0.0) identifies the selected release. This is a bounded compatibility choice for existing `pi-*` usage, **not a latest-version claim**. The archived font-based package trades a small, consistent fix for asset cost and eventual maintenance migration; current standalone SVG packages can be evaluated separately. Browser computed pseudo-content/font family and screenshots verify that glyphs render.

The build emits a **35.14 kB WOFF2** icon font plus fallback formats. Emitted files are not all necessarily transferred. Comparing this dispatch's before/final build entries: customer CSS grows from 26.02 to about 38.4 kB, admin CSS from 17.99 to about 31.9 kB; admin entry JS grows from 399.59 to 414.33 kB as navigation now uses Drawer. Customer entry JS is about 445 kB after removing interactive Rating from the shared discovery path. Exact raw/gzip artifacts are in build logs; these are **individual chunks, not total route payloads or measured performance gains**.

## Browser evidence and measurements

`browser.cjs` launches **actual headless Chrome 156.0.8078.80**, with a private temporary profile and direct CDP. All API/OIDC and non-preview network requests are intercepted; fixtures include long names, a long unbroken customer/SKU identifier, in-stock/out-of-stock items, loaded cart/orders/products, request errors, a held order request, payment rejection and payment pending. No real identity/backend writes occur.

Preview ports **16573/16574** were checked free before starting the two built apps with `--strictPort`. A frozen baseline was reconstructed from `start-sources.json` in a temporary directory with `reconstruct-baseline.py`, using installed dependencies and an explicit alias to its frozen shared sources. Its separate ports **16575/16576** were also checked free. This allowed settled baseline Drawer screenshots without reverting the UI worktree. `baseline-render.json` records the snapshot hash and temporary directory. No dirty original-checkout files were copied.

The main matrix measures **360, 390, 768, 1024 and 1440 CSS pixels**, height 950, DPR 1, `mobile:false` to prevent horizontal-overflow auto-scaling from disguising layout defects. This is responsive browser emulation, not physical-device proof. The extra **720×475 CSS-pixel** states halve a 1440×950 viewport, providing a **200% reflow equivalent**, not an OS/text-only/native browser zoom test. They also have zero document overflow.

- Settled baseline: [before-browser-results.json](before-browser-results.json), **61** screen/width observations; command receipt `before-browser-settled.json` (exit 0).
- Final full matrix: [after-browser-results.json](after-browser-results.json), **77** screen/width observations, **51** interaction observations; `browser-completion-verified.json` (exit 0).
- Focused follow-up: **17 screen/width and 43 interaction observations**, `interaction-verified-browser-results.json` and `interaction-verified-run.json` explicitly check that a brand checkbox keeps focus through refresh, and that a visible desktop slider has focus before narrowing the viewport. This supplements the full matrix on the same application source.
- Final full matrix: **zero whole-document horizontal overflow in all 77 observations**, zero captured runtime exceptions and no failed interaction assertion. Full card/header/table/drawer rectangles and overflow offenders are retained in JSON. Tables/category navigation can still scroll within their own regions by design.

At 390px, representative `document.scrollWidth − document.clientWidth` measurements are:

| Screen | Before | After |
| --- | ---: | ---: |
| Customer cart | 1068px | 0px |
| Checkout review | 692px | 0px |
| Checkout shipping/payment | 227px | 0px |
| Admin orders | 1043px | 0px |
| Admin products | 891px | 0px |
| Home/search | 0px, but cramped cards/search | 0px, 171px cards and full-width search |

Customer home cards were about 98px wide in the prior 390px baseline and are **171px** now. Detail drawers at 390px settle at **x=0, width=390, height=950**, with no inner-width spill. View/Edit actions are inside the viewport at initial table scroll position for all five widths. The baseline's maximum document overflow was **1098px** at the 360px cart.

### Actual interaction methods

After-change pointer helpers require a real hit test and issue CDP mouse events; they **fail** if another element obscures the intended target. They do not silently convert pointer checks to keyboard activation. The baseline may use a separately logged focus+Enter fallback to reach controls its existing overflow hides. Explicit keyboard helpers issue CDP `Input.dispatchKeyEvent`; the card touch check uses CDP `Input.dispatchTouchEvent`. These are real browser input paths, not jsdom event dispatches or invented component events, and are not physical-device testing.

Verified interactions include link→cart Tab order; cart Enter/Space/touch with one API submission and no navigation; link Enter navigation without another add; filter Space/Enter/arrow-key actions and closed tag clearing; mobile navigation Tab/Shift+Tab/Escape, trigger restoration, route close and open-nav resize; bounded detail Drawer Escape/return; invalid payment summary/error focus; held processing dialog Tab trapping and Escape behavior; payment rejection focus/next-step copy; pending result heading focus and honest status. Web storage is checked for absence of the synthetic card/CVV payload. The request bodies/backend protocol are unchanged.

### Accessibility automation and contrast

The final full matrix runs **40 axe audits**, with WCAG 2 A/AA, 2.1 AA and 2.2 AA tags, across meaningful mobile/desktop, error, open-filter, navigation, detail, processing, rejection, pending and reflow states. **Zero violations were detected in these states**. Incomplete axe checks remain (including contrast, dynamic ID references and hidden-focus checks); they are retained in JSON and are not counted as a compliance pass. Keyboard/manual screenshot checks supplement the scanner. Screen-reader interoperability remains untested.

The order View icon needed an additional non-text check beyond axe. The reconstructed original baseline, measured after keyboard focus transitions settle, has cyan foreground/focus on white at **2.7714:1** with a 1px outline (`contrast-baseline-settled-browser-results.json`). In the intermediate UI with a sticky gray action cell, the same foreground also remained insufficient. Computed foreground **rgb(14,165,233)** on **rgb(249,250,251)** gives **2.6520:1**; it was changed to existing primary-700 **rgb(29,78,216)**, giving **6.4129:1**. Final keyboard focus has the same nontransparent foreground, **2px outline / 3px offset**, also **6.4129:1** against that background. The ghost button's transparent decorative border is not its identifying visual cue. The [W3C non-text contrast explanation](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) supports using underlying/computed colors and at least 3:1 for essential control information. `contrast-verified-run.json` exits 0 only with explicit icon/focus ratio and visible-outline assertions.

Raw early probes read the animated outline at transition start and reported transparency; `contrast-after-settled-browser-results.json` and `contrast-verified-browser-results.json` wait for finite transitions and verify the final color. Raw probes are retained as observations, not described as focus passes.

### Screenshot index

Screenshots are actual browser captures after finite animations/fonts settle; they are not generated mockups. Representative pairs:

| Screen | Before | After |
| --- | --- | --- |
| Customer home/mobile | [before](before-customer-home-390.png) | [after](after-customer-home-390.png) |
| Customer search/mobile | [before](before-customer-search-390.png) | [after](after-customer-search-390.png) |
| Customer cart/mobile | [before](before-customer-cart-390.png) | [after](after-customer-cart-390.png) |
| Product detail/mobile | [before](before-customer-detail-390.png) | [after](after-customer-detail-390.png) |
| Checkout shipping errors | [before](before-checkout-shipping-errors-390.png) | [after](after-checkout-shipping-errors-390.png) |
| Admin orders/mobile | [before](before-admin-orders-390.png) | [after](after-admin-orders-390.png) |
| Admin product Drawer | [before](before-admin-product-drawer-390.png) | [after](after-admin-product-drawer-390.png) |
| Customer home/desktop | [before](before-customer-home-1440.png) | [after](after-customer-home-1440.png) |
| Admin orders/desktop | [before](before-admin-orders-1440.png) | [after](after-admin-orders-1440.png) |

Additional states: [mobile navigation](after-admin-mobile-navigation-390.png), [processing](after-checkout-processing-390.png), [rejection](after-checkout-payment-rejected-390.png), [pending](after-checkout-pending-390.png), [customer reflow](after-reflow-equivalent-customer-720.png), [admin reflow](after-reflow-equivalent-admin-720.png). Other captures and all numeric measurements are indexed by screen in the JSON reports. The initial pre-settling captures are retained under `initial-before-browser/`; use the settled pairs above for review.

## Permanent tests and executed validation

**24 additional UI tests** are retained: 19 customer and 5 admin, on top of the earlier 356-test state. They exercise native link/button separation, duplicate pending adds, static rating semantics, filter disclosure/handle names/arrow behavior, closed clearing, breakpoint focus return, deferred request checkbox-focus retention, shipping/payment error associations and error links, Stepper ID relationships/selection, processing focus priority, non-final order copy, native Home navigation, collapsed/mobile admin navigation, route/resize/trigger focus handling, price editor focus and product error Retry. Real browser tests cover native key/touch behavior and focus traps that jsdom cannot establish.

All commands below ran from `frontend/`, except the tool runner's explicit per-app working directories. Full argv/cwd/exit records and stdout are stored separately as `.json`/`.txt`; no unexecuted check is called passing.

| Check | Final result and receipt |
| --- | --- |
| Targeted customer UI tests | Initial 4 files / 41 pass; subsequent customer targets pass. Final filter/fence target **3 files / 38 tests**: `filter-focus-targeted-green.json`; detail/processing target **2 files / 12 tests**: `targeted-detail-final.json`. |
| Targeted admin UI tests | Relevant layout/navigation/Drawer/orders/products/forms tested. Final price/retry target **8 tests pass**: `targeted-admin-price-retry.json`; the full suite below includes all final admin changes. |
| `npm run test:unit --workspaces --if-present -- --run` | **Exit 0: customer 32 files / 286 tests; admin 20 files / 94 tests; 380 total.** `final-unit-verified.json` / `.txt`. Includes the earlier 27 regressions. |
| `npm run type-check --workspaces --if-present` | **Exit 0**, both apps: `final-type-verified.json`. |
| `npm run build --workspaces --if-present` | **Exit 0**, both apps including type checking: `final-build-verified.json`. Shared is checked through both consumers. |
| ESLint | Existing config and `--no-fix` on all changed Vue/TS files in each app: `eslint-verified.json`; shared uses the unchanged customer rules with `fix:false`: `final-shared-eslint.json`. No rule/gate disabled. |
| Oxlint | Existing per-app config and no `--fix`, plus changed shared TS: `oxlint-verified.json`. |
| Prettier | `--check` on changed source, manifests/lock/admin HTML: `format-verified.json`. Shared uses the existing customer single-quote/no-semicolon config to preserve nearby conventions. |
| Browser/contrast | Full matrix `browser-completion-verified.json`; supplemental `interaction-verified-run.json`; assertion-bearing `contrast-verified-run.json`. |
| `git diff --check` and provenance hashes | Final source whitespace/provenance checks recorded in `completion-manifest.json`; prior evidence remains intact. |

### Failures retained and corrected

- Initial baseline browser attempts failed on mobile overflow/hit coordinates, async form validation and initial Drawer timing. Partial URL/DOM/runtime diagnostics and receipts are retained; later baseline capture succeeded after correcting the harness. The very first command-receipt/browser-result name collision was corrected to separate result filenames; receipts are not presented as browser data.
- Intermediate browser probes exposed real UI defects: hidden cart CTA/navigation, incorrect Stepper ownership, lost filter focus, insufficient icon contrast, and heading-before-alert focus priority. New radio-based card display briefly introduced nested interactive semantics; it was replaced by truly static stars, then re-audited.
- Deterministic new regressions ran **red then green** for breakpoint focus (`filter-resize-red`: 10 pass/1 fail → `filter-resize-green`: 11 pass) and a focused brand checkbox during a deferred request (`filter-request-focus-red`: 10 pass/1 fail → final filter target pass). The earlier task's three baseline-red/green artifacts are unchanged in their own directory.
- Intermediate unit/type checks caught test-only DOM-wrapper typing, a library component-ref typing mismatch, and jsdom Select's absent `matchMedia`. Those were corrected using actual DOM IDs and a scoped unrelated Select stub; no production quality gate was weakened. A mistaken repository-root npm check failed ENOENT and was rerun from the required `frontend/` cwd.
- ESLint rejected a new autofocus attribute. Modal focus moved to the explicit open event, with real-browser trap/return checks. Several intermediate browser runs failed due to timing/selector/syntax errors in the harness; one failed run required termination of its owned Node process and records exit -15. They are **not** counted as passing runs. Only the named final receipts above establish acceptance.

## Cleanup and reproducibility

`cleanup.json` records verified command/cwd ownership before terminating preview listener PIDs **90581, 90648, 14377, 14427**. Ports 16573–16576 have no remaining listener; the task's temporary reconstructed baseline was removed, and no task-owned Chrome process remains. Application source, screenshots, reports and both earlier evidence directories are preserved. `run-check.py` is a durable version of the command-receipt wrapper used during execution; use it from `frontend/` with a new label and the desired command. Browser evidence requires starting new isolated previews on checked-free ports first; baseline reconstruction is repeatable from the frozen source snapshot.

## Self-review and remaining boundaries

Reviewed the dispatch diff against the frozen start source and visually inspected representative mobile/desktop screenshots. Checked narrow scrolling and visible actions, touch/keyboard separation, loading/stock/duplicate-submit failure paths, navigation close/route/resize behavior, Drawer bounds/trap/return, label/error ID resolution, invalid submit and processing/result focus, request-refresh focus, keyword resets, closed filter clearing and the earlier search fence tests. Findings fixed during this review are listed above; the source/evidence hashes distinguish the final state from intermediate probes.

Remaining release work is **not marked resolved**:

- F01/F02 checkout recovery, ambiguous writes/idempotency and customer auth startup/deep-link integration; current retry/backend semantics and generic client error normalization remain outside this UI patch. Fixtures do not prove backend payment/order correctness.
- General overlapping cart mutations and late server-response reconciliation remain beyond the earlier isolated rollback fix. No new concurrency claim is made.
- F06/F07 server catalog pagination/search/sort and accurate Orders page-size/offset controls; sticky actions and load-error UI do not repair those API contracts. Brand choices remain inferred from loaded matches, not authoritative global facets.
- F09 dependency advisories and deprecated themes; F10 browser/a11y CI gating; F12 duplicated token/theme ownership; F13 broader error/maintenance recovery; F14 OIDC/session/WebSocket recovery; F16 route performance/SSR/redesign. No framework/theme major migration or security sweep occurred.
- Real Keycloak/full-stack E2E, GitHub Actions/Node 22 parity, Safari/WebKit/Firefox, physical phones, assistive technology/screen readers, forced colors, OS/text-only zoom and full WCAG conformance were not tested. This host uses Node **24.21.0**, npm **11.19.0** and Chromium only. Axe incomplete results and synthetic fixtures limit the claim.
- No locale/currency/address-policy or real-payment provider change. Card/CVV remain memory-only demo input and are absent from the order payload and tested web-storage state. No recovery data from the original checkout was imported.

A reviewer can approve this local UI batch for integration independently of the remaining reliability/API/security tracks. Product choices still needed are the supported browser/device policy, depth of mobile admin editing, and which reviewed checkout-recovery commit to integrate before a release.
