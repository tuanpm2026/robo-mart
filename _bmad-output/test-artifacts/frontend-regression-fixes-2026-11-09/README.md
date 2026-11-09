# Frontend regression fixes — 2026-11-09

## Outcome and provenance

The three requested defects are **fixed locally and tested, not integrated or deployed**:

1. F08: rejection of an optimistic add to an existing cart item restores its previous quantity and subtotal.
2. F11: obsolete search responses cannot replace the current search's results, pagination, error or loading state, including after clearing/resetting and during load-more.
3. F11: sidebar filter events replace the complete filter snapshot; cleared brand/price/rating fields leave the API request and the current route keyword remains authoritative.

Worktree: `/Users/tuanphung/orca/workspaces/robo-mart/codex-frontend-upgrade-assessment`, branch `tuanpm2026/codex-frontend-upgrade-assessment`, baseline HEAD `4ec50bbaa04933c43a37066f88c293e056c24012`. The source changes remain uncommitted. Node `v24.21.0`, npm `11.19.0`; installed locked dependencies were reused without an install or upgrade. The original `/Users/tuanphung/work/robo-mart` checkout was not modified or used to run checks.

The existing [independent review evidence](../frontend-upgrade-review-2026-11-09/README.md) was preserved. All **17** entries in its `evidence-sha256.json` matched on the final preservation check. Root/app manifests and `frontend/package-lock.json` still match HEAD. No commit, push, merge, backend write, deployment, dependency change or unrelated UI upgrade was performed.

## Implementation

| File, relative to `frontend/customer-website/` | Change |
| --- | --- |
| `src/stores/useCartStore.ts`:28–49 | Copy each cart item into the rollback snapshot instead of retaining shared item objects. Optimistic behavior, new-item handling, rejection propagation and authoritative server-response assignment remain in place. |
| `src/stores/useProductStore.ts`:50–75,97–121 | Monotonic search request identity fences success, catch and finally. A replacement first page clears old results/pagination; load-more ignores duplicate pending clicks; clear/reset retires pending searches and pages. Submitted filters are copied. |
| `src/views/SearchResultsView.vue`:34–69 | Treat sidebar emissions as complete snapshots and apply the current route keyword last. Keyword navigation also clears the sidebar controls. |
| `src/components/product/FilterSidebar.vue`:9–25,53–70 | Initialize controls from existing filters and expose targeted `clearFilter` behavior so tag removal and subsequent sidebar emissions agree. Each price bound can be cleared without clearing the other bound. |
| `src/views/SearchResultsView.vue`:81–92 | Replace the unsupported `Tag` `removable`/`remove` assumption with a real native button in the existing Tag slot, with an accessible removal name. This is part of the filter-clearing repair. |

Permanent regressions follow existing Vitest/Vue Test Utils/Pinia conventions:

- `src/stores/__tests__/useCartStore.spec.ts`: two added tests for existing-item rejection and authoritative server quantities/prices; existing new-item and quantity/remove regressions remain.
- `src/stores/__tests__/useProductStore.search.spec.ts`: **16** deterministic tests for response ordering, stale success/error/finally, latest failure, reset/clear, copied filters, append deduplication, pagination, obsolete append, append failure and retry.
- `src/views/__tests__/SearchResultsView.filters.spec.ts`: **9** actual-view/sidebar integration tests. Checkbox/Slider/Rating use their supported model events; the real filter-removal buttons are activated with DOM `click`, then a different sidebar control changes before the next request is asserted. Keyword changes and initial control state are included.

There are **27 added tests**: customer grows from 240 to 267. Search ordering tests settle deferred promises explicitly; no timer, sleep or speed-dependent ordering is used. No test asserts that all cart concurrency is solved.

## Red/green evidence

Every command metadata JSON records argv, cwd, timestamp and subprocess exit status. `.txt` logs are durable untracked artifacts that are not excluded by the repository's `*.log` ignore rule; ignored local `.log` mirrors are not required to read this report.

| Stage | Observed result | Evidence |
| --- | --- | --- |
| Initial permanent tests, before source edits | **18 failed / 21 passed**, 3 files failed, exit **1** | [red-targeted.txt](red-targeted.txt), [metadata](red-targeted.json) |
| First focused fixes | **39 passed**, exit **0** | [green-targeted.txt](green-targeted.txt), [metadata](green-targeted.json) |
| Self-review: removed filter later revived by sidebar | **4 failed / 4 passed**, exit **1** | [self-review-red.txt](self-review-red.txt), [metadata](self-review-red.json); this intermediate test used a synthetic Tag remove event and was subsequently strengthened |
| Self-review: missing actual removal control | **5 failed / 4 passed**, exit **1** | [self-review-dom-red.txt](self-review-dom-red.txt), [metadata](self-review-dom-red.json) |
| Final regression tests against frozen baseline source, in an isolated temporary frontend copy | **25 failed / 22 passed**, 3 files failed, exit **1** | [final-tests-baseline-red.txt](final-tests-baseline-red.txt), [metadata and exact test hashes](final-tests-baseline-red.json) |
| Final focused tests against fixed source | **47 passed**, 3 files, exit **0** | [targeted-final.txt](targeted-final.txt), [metadata](targeted-final.json) |
| Final complete workspace tests | Customer **29 files / 267 tests**, admin **19 files / 89 tests**, total **356 passed**, exit **0** | [final-unit-workspaces.txt](final-unit-workspaces.txt), [metadata](final-unit-workspaces.json) |

The final baseline comparison uses the current tests/configuration but reads the four changed production files from the frozen baseline commit into a disposable copy. It reuses the installed locked modules. It never swaps source files in this worktree or the original checkout. Reproduce it from the repository root:

```bash
python3 _bmad-output/test-artifacts/frontend-regression-fixes-2026-11-09/reproduce-baseline-red.py
```

Expected exit on this baseline is **1**, with the failures recorded above. The original baseline's 329 passing tests and the independent review's three failing probes remain separate historical evidence; neither was overwritten.

The focused command, run from `frontend/`, is:

```bash
npm run test:unit --workspace=customer-website -- --run \
  src/stores/__tests__/useCartStore.spec.ts \
  src/stores/__tests__/useProductStore.search.spec.ts \
  src/views/__tests__/SearchResultsView.filters.spec.ts
```

Intermediate attempts are also retained rather than hidden: initial read-only formatting found three files requiring formatting; Oxlint found conditional assertions in an intermediate parameterized test, which were replaced with complete expected-request snapshots. An intermediate DOM-button test selected `aria-label` containing `+` through a CSS attribute selector and failed even though its rendered HTML contained the button. Final tests locate real button elements by their exact accessible-name attribute, assert existence and trigger DOM clicks. `targeted-dom-green.json` records that failed intermediate attempt despite its provisional filename; **the authoritative final focused result is `targeted-final.json` (exit 0)**.

## Final validation

From `frontend/`, after the self-review fixes:

| Command | Exit / result | Evidence |
| --- | --- | --- |
| `npm run test:unit --workspaces --if-present -- --run` | **0**, 356 tests passed | [unit log](final-unit-workspaces.txt) |
| `npm run type-check --workspaces --if-present` | **0**, both apps | [type-check log](final-typecheck-workspaces.txt), [metadata](final-typecheck-workspaces.json) |
| `npm run build --workspaces --if-present` | **0**, both apps including configured type checks | [build log](final-build-workspaces.txt), [metadata](final-build-workspaces.json) |

Shared has no matching scripts; `--if-present` does not create or claim a separate shared test/build.

From `frontend/customer-website/`, the following read-only commands each passed with **exit 0**:

```bash
../node_modules/.bin/prettier --check \
  src/stores/useCartStore.ts src/stores/useProductStore.ts \
  src/views/SearchResultsView.vue src/components/product/FilterSidebar.vue \
  src/stores/__tests__/useCartStore.spec.ts \
  src/stores/__tests__/useProductStore.search.spec.ts \
  src/views/__tests__/SearchResultsView.filters.spec.ts

../node_modules/.bin/eslint \
  src/stores/useCartStore.ts src/stores/useProductStore.ts \
  src/views/SearchResultsView.vue src/components/product/FilterSidebar.vue \
  src/stores/__tests__/useCartStore.spec.ts \
  src/stores/__tests__/useProductStore.search.spec.ts \
  src/views/__tests__/SearchResultsView.filters.spec.ts

../node_modules/.bin/oxlint \
  src/stores/useCartStore.ts src/stores/useProductStore.ts \
  src/views/SearchResultsView.vue src/components/product/FilterSidebar.vue \
  src/stores/__tests__/useCartStore.spec.ts \
  src/stores/__tests__/useProductStore.search.spec.ts \
  src/views/__tests__/SearchResultsView.filters.spec.ts
```

See [Prettier](final-prettier.txt), [ESLint](final-eslint.txt), [Oxlint](final-oxlint.txt) and their matching JSON metadata. No `lint`/`format` autofix script was used. `git diff --check` passed. All prescribed checks were executable; there was no missing-dependency blocker.

## Self-review and remaining boundaries

| Risk/failure path reviewed | Evidence/disposition |
| --- | --- |
| Existing cart item aliases rollback snapshot | Fixed by copying each flat `CartItem`; tested quantity/subtotal 1/10 → optimistic add → rejection → 1/10. |
| Successful cart add returns changed quantities/prices | Permanent regression verifies server response wins; existing new-item rejection test remains passing. |
| Overlapping cart mutations or late full-cart responses | **Not solved.** Source walkthrough: A snapshots quantity 1, B starts after A's optimistic change, B succeeds, then A rejects and restores its whole earlier snapshot. Likewise, a late successful response can replace a newer full-cart response. The clone fixes object aliasing, not operation ordering; serialization/versioned reconciliation needs separate work. No live concurrency test or blanket concurrency guarantee is claimed. |
| Stale search success changes results/pagination; stale catch/finally changes latest error/loading | All publication paths are guarded by request identity; deferred tests cover older-first and older-last completion and the latest request's failure. |
| Old page appends after filter change, clear or reset | Replacement page clears previous pagination; clear/reset retires requests. Deferred tests prove old pages do not reappear, duplicate load-more is skipped, and append failure retries the same next page without discarding the current page. |
| Filter snapshot drops only one field | Actual sidebar tests assert omitted brand, both price bounds and rating leave requests while other selections and keyword remain. |
| Keyword changes but old sidebar state remains | Fixed by clearing controls on keyword navigation and applying the route keyword after snapshot fields; regression changes a control after navigation. |
| Tag removal followed by another sidebar input revives a removed filter | Reproduced during review and fixed through targeted sidebar clearing. Tests cover all four keys and initialization from existing filters. |
| Unsupported PrimeVue Tag event makes removal unreachable | Installed `primevue/tag` implementation/types were inspected; removal is now a real native button with an accessible name. Final assertions use DOM click, not an invented remove event. Native keyboard activation was not exercised in a browser. |
| Duplicate Clear All/keyword requests | Existing explicit search plus reactive sidebar emission can issue the same replacement query twice. Request fencing prevents obsolete publication; eliminating redundant requests is not claimed in this patch. |
| Other product requests and autocomplete | Browse/detail and autocomplete request ordering are unchanged. The product store still shares `isLoading`/`error` across actions; this patch isolates stale **search-vs-search** publication, not every possible cross-action overlap. |
| Other F11 recommendations | Search error/empty UI, multi-brand API support, category data and URL filter persistence remain assessment follow-ups. F11 as a whole is **not** marked resolved. |

Tests use mocked APIs and jsdom. No real Keycloak/backend, browser E2E, physical device, screen reader, coverage percentage, dependency audit rerun or Node 22 CI execution is claimed. Release validation must use the final integration commit together with the unrelated checkout-recovery/Saga work; this task neither imported nor validated those dirty original-checkout changes.
