# Investigation: CORS — `Access-Control-Allow-Origin` missing for https://admin.tuanpm.xyz

## Hand-off Brief

1. **What happened.** Browser at `https://admin.tuanpm.xyz` calling the API gateway is blocked by CORS — no `Access-Control-Allow-Origin` header on the response. The allowed-origin **configuration is correct** (configmap + deployment wired), so the missing header is a request-flow/filter-ordering defect, not a config typo.
2. **Where the case stands.** Strong deduced root cause: CORS is applied by a standalone `CorsWebFilter` ordered **after** Spring Security's filter chain, and Spring Security has **no `CorsConfigurationSource` bean** to satisfy `http.cors(withDefaults())`. So CORS preflight `OPTIONS` (and any 401/403 auth-error) on protected routes are answered by Security before CORS headers are ever added → browser sees no `Access-Control-Allow-Origin`.
3. **What's needed next.** Confirm with the actual failing request (Network tab: URL + method + status) or a `curl` preflight against `api.tuanpm.xyz`. Then fix by handling CORS *inside* the Security chain (expose a `CorsConfigurationSource` bean) — route to `bmad-quick-dev`.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-27                                                                 |
| Status           | Active                                                                     |
| System           | robo-mart prod (k8s, ingress-nginx + cert-manager), api-gateway = Spring Cloud Gateway (WebFlux), Spring Boot 4 |
| Evidence sources | Source code (api-gateway), k8s manifests (base + ingress-tls overlay), unit test |

## Problem Statement

> lỗi from origin 'https://admin.tuanpm.xyz' has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header is present on the requested resource.

User-reported (treated as hypothesis). Independently verified below.

## Evidence Inventory

| Source   | Status     | Notes     |
| -------- | ---------- | --------- |
| api-gateway CORS code | Available | `CorsConfig.java`, `GatewaySecurityConfig.java`, `application.yml` |
| k8s config | Available | `configmap.yml`, `services/api-gateway/deployment.yml`, `overlays/ingress-tls/ingress.yml` |
| Unit test | Available | `CorsConfigTest.java` — bean-existence only, no end-to-end CORS coverage |
| **Failing request details** | **Missing** | Browser Network tab: exact URL, method (OPTIONS vs GET/POST), HTTP status of the blocked response |
| **Running-pod env** | **Missing** | `kubectl` view of the live api-gateway pod env (rules out stale `:latest` image / old config) |

## Confirmed Findings

### Finding 1: Allowed-origin config is correct and wired into the pod
**Evidence:** `infra/k8s/base/configmap.yml:53` → `CORS_ADMIN_ORIGIN: "https://admin.tuanpm.xyz"`; `infra/k8s/base/services/api-gateway/deployment.yml:36-38` → `envFrom: - configMapRef: name: robomart-config`; `backend/api-gateway/src/main/resources/application.yml:41` → `admin-origin: ${CORS_ADMIN_ORIGIN:...}`; consumed at `CorsConfig.java:16-17,26`.
**Detail:** The admin origin reaches the gateway correctly. The symptom is **not** a wrong/missing origin value.

### Finding 2: CORS is handled by a standalone `CorsWebFilter` with no explicit order
**Evidence:** `CorsConfig.java:22-36` registers a `CorsWebFilter` bean; no `@Order` / `Ordered` declared.
**Detail:** A `WebFilter` bean with no order defaults to `Ordered.LOWEST_PRECEDENCE` — it runs **last**.

### Finding 3: No `CorsConfigurationSource` bean exists; Security's `cors()` has nothing to use
**Evidence:** `CorsConfig.java:33` creates `UrlBasedCorsConfigurationSource` as a **local variable** inside the filter, not a bean. Grep for `CorsConfigurationSource` beans across `backend/**/*.java` returns only this local use. `GatewaySecurityConfig.java:27` calls `.cors(Customizer.withDefaults())`.
**Detail:** In Spring Security WebFlux, `.cors(withDefaults())` resolves a `CorsConfigurationSource` bean to apply CORS *within* the security chain (and to short-circuit preflight). With no such bean, the security chain does **no** CORS handling.

### Finding 4: Protected routes require auth; preflight has no credentials
**Evidence:** `GatewaySecurityConfig.java:35-36` → `/api/v1/admin/**` `hasRole("ADMIN")`, `/api/v1/orders/**` `authenticated()`, `.anyExchange().authenticated()`.
**Detail:** A CORS preflight `OPTIONS` carries no Authorization header; against these matchers Security will challenge it (401/403).

### Finding 5: Gateway `globalcors` adds no origins; only a dedup filter remains
**Evidence:** `application.yml:18-21` → `globalcors.add-to-simple-url-handler-mapping: true` (no `cors-configurations` block); `default-filters: DedupeResponseHeader=Access-Control-Allow-Origin ... RETAIN_FIRST`.
**Detail:** The gateway routing layer contributes no CORS config; all CORS depends on the (late-ordered) `CorsWebFilter`. The `DedupeResponseHeader` filter is a leftover guard against duplicate ACAO headers.

### Finding 6: Topology is genuinely cross-origin
**Evidence:** `overlays/ingress-tls/ingress.yml` → `admin.tuanpm.xyz` → `admin-dashboard` (static SPA); `api.tuanpm.xyz` → `api-gateway:8080`.
**Detail:** SPA at `admin.tuanpm.xyz` makes cross-origin XHR to `api.tuanpm.xyz`; the gateway must emit `Access-Control-Allow-Origin: https://admin.tuanpm.xyz`.

### Finding 7: Test coverage cannot catch this
**Evidence:** `CorsConfigTest.java:22-49` — all three tests assert only `filter != null`.
**Detail:** No test exercises a preflight or an auth-rejected request through the security chain, so the ordering defect is invisible to CI.

## Deduced Conclusions

### Deduction 1: Preflight / auth-error responses never receive CORS headers
**Based on:** Findings 2, 3, 4.
**Reasoning:** Spring Security's `WebFilterChainProxy` runs near the head of the WebFlux chain (order `-100`); the `CorsWebFilter` runs at `LOWEST_PRECEDENCE` (Finding 2). For any request Security rejects — a preflight `OPTIONS` to an authenticated/admin path, or a 401/403 on a protected route (Finding 4) — Security writes the response **before** the `CorsWebFilter` executes. Because Security itself has no `CorsConfigurationSource` (Finding 3), it neither permits the preflight nor adds CORS headers.
**Conclusion:** Such responses carry **no** `Access-Control-Allow-Origin` → exactly the reported symptom. Simple/allowed GETs that pass auth *would* get the header (CorsWebFilter eventually runs), which is why the failure is intermittent-by-endpoint, not total.

## Hypothesized Paths

### Hypothesis 1: Late-ordered CORS + Security-without-CorsConfigurationSource (LEADING)
**Status:** Open (strongly supported by Deduction 1)
**Theory:** Preflight `OPTIONS` and/or auth-error responses on protected gateway routes lack `Access-Control-Allow-Origin` because CORS is applied after Security and Security has no CORS source.
**Supporting indicators:** Findings 2–4; config is otherwise correct (Finding 1).
**Would confirm:** Network tab shows the blocked request is an `OPTIONS` preflight, or a 401/403/5xx, on a protected path with no `Access-Control-Allow-Origin`. OR `curl -i -X OPTIONS https://api.tuanpm.xyz/api/v1/admin/... -H 'Origin: https://admin.tuanpm.xyz' -H 'Access-Control-Request-Method: GET'` returns no ACAO header.
**Would refute:** A simple unauthenticated GET to a `permitAll` path (e.g. `/api/v1/products`) *also* lacks the header → points instead to H2/H3.

### Hypothesis 2: Stale running config (image `:latest` / pod predates configmap)
**Status:** Open
**Theory:** The live pod has old env (origins defaulting to `localhost`), so `admin.tuanpm.xyz` is genuinely not allow-listed at runtime.
**Supporting indicators:** `deployment.yml:25` uses `image: ...:latest`.
**Would confirm:** `kubectl exec` / `kubectl set env --list` shows `CORS_ADMIN_ORIGIN` absent or wrong; or a pod older than the configmap.
**Would refute:** Pod env shows the correct origin → back to H1.

### Hypothesis 3: SPA calling the wrong host (e.g. Keycloak `auth.tuanpm.xyz`)
**Status:** Open
**Theory:** Admin SPA issues XHR to a host that returns no CORS header — notably `auth.tuanpm.xyz`, which is **absent from the ingress rules** (see Side Findings).
**Would confirm:** Network tab shows the blocked URL is not `api.tuanpm.xyz`.
**Would refute:** Blocked URL is on `api.tuanpm.xyz` → H1.

## Missing Evidence

| Gap | Impact | How to Obtain |
| --- | --- | --- |
| Exact failing request (URL, method, status) | Distinguishes H1 vs H2 vs H3 decisively | Browser DevTools → Network → click the failed/red request → Headers |
| Live pod env | Rules out H2 | `kubectl -n robomart exec deploy/api-gateway -- env \| grep CORS` |
| Preflight curl result | Confirms H1 deterministically | `curl -i -X OPTIONS` with `Origin` + `Access-Control-Request-Method` headers against the real route |

## Source Code Trace

| Element | Detail |
| --- | --- |
| Error origin | `backend/api-gateway/src/main/java/com/robomart/gateway/config/CorsConfig.java:22-36` (late-ordered filter) + `GatewaySecurityConfig.java:27` (`cors(withDefaults())` with no source) |
| Trigger | Cross-origin request from `https://admin.tuanpm.xyz` to `https://api.tuanpm.xyz` that is a preflight `OPTIONS` or an auth-rejected request |
| Condition | Security filter chain (order -100) responds before `CorsWebFilter` (LOWEST_PRECEDENCE); no `CorsConfigurationSource` bean for Security to apply |
| Related files | `application.yml:16-21,39-42`, `infra/k8s/base/configmap.yml:51-54`, `services/api-gateway/deployment.yml:36-38`, `overlays/ingress-tls/ingress.yml` |

## Conclusion

**Confidence:** Medium-High (root cause **deduced** from code; one repro step elevates it to High)

The allow-list config is correct and reaches the pod (Finding 1), so this is **not** a missing-origin typo. The most probable root cause is architectural: CORS is enforced by a `CorsWebFilter` ordered after Spring Security, while the Security chain itself has no `CorsConfigurationSource` to satisfy `http.cors(withDefaults())`. Consequently CORS **preflight `OPTIONS`** and **auth-error (401/403)** responses on protected routes are emitted with no `Access-Control-Allow-Origin` header — matching the symptom. Confirm by reading the actual blocked request or running a preflight `curl`.

## Recommended Next Steps

### Fix direction (do not implement yet — investigation stops at diagnosis)
Make Spring Security own CORS so it applies to preflight and auth-error responses:
- Expose the `UrlBasedCorsConfigurationSource` as a **`@Bean CorsConfigurationSource corsConfigurationSource()`**; `GatewaySecurityConfig`'s `.cors(withDefaults())` will auto-wire it, permit preflight, and emit headers even on 401/403.
- Then remove the standalone `CorsWebFilter` bean (now redundant) — or, as a minimal alternative, annotate it `@Order(Ordered.HIGHEST_PRECEDENCE)` so it runs before Security. The bean approach is the WebFlux-idiomatic fix and also removes the need for the `DedupeResponseHeader` workaround.
- Add a `WebTestClient` test that asserts an `OPTIONS` preflight from `https://admin.tuanpm.xyz` to a protected route returns `Access-Control-Allow-Origin` (closes the Finding 7 gap).

### Diagnostic (to reach High confidence first)
1. DevTools → Network → inspect the blocked request: is it `OPTIONS`? what status? what host/path?
2. `curl -i -X OPTIONS https://api.tuanpm.xyz/api/v1/admin/<route> -H 'Origin: https://admin.tuanpm.xyz' -H 'Access-Control-Request-Method: GET'` — check for `Access-Control-Allow-Origin`.
3. `kubectl -n robomart exec deploy/api-gateway -- env | grep CORS` — rule out H2.

## Reproduction Plan

Local: start api-gateway, send `curl -i -X OPTIONS http://localhost:8080/api/v1/orders/123 -H 'Origin: http://localhost:5174' -H 'Access-Control-Request-Method: GET'`. Expectation if H1 holds: response is a 401/empty with **no** `Access-Control-Allow-Origin`, while the same preflight to `/api/v1/products` (permitAll) returns the header — demonstrating the auth-gated CORS gap.

## Side Findings

- **`auth.tuanpm.xyz` is absent from the ingress** (`overlays/ingress-tls/ingress.yml` rules list only `app`/`admin`/`api`), yet it's referenced as the OIDC issuer (`configmap.yml:18-19`). If Keycloak is reached via this ingress, OIDC/token XHR from the admin SPA could itself fail (CORS or routing). Confirm where `auth.tuanpm.xyz` is served. (Evidence: Confirmed for ingress; impact Hypothesized.)
- **`image: ...:latest`** on the gateway deployment makes "which build is actually running" non-deterministic — complicates ruling out stale config (relates to H2).
