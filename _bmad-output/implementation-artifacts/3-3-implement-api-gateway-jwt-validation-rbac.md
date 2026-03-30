# Story 3.3: Implement API Gateway JWT Validation & RBAC

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a system,
I want the API Gateway to validate JWT tokens and enforce role-based access control on every request,
So that protected endpoints are only accessible by authorized users.

## Acceptance Criteria

1. **Given** API Gateway with JwtValidationFilter **When** a request arrives for a protected endpoint (e.g., `/api/v1/orders/**`) **Then** the Gateway validates the JWT token from the Authorization header, and rejects with 401 if missing or invalid (FR41)
2. **Given** a valid JWT with Customer role **When** accessing `/api/v1/admin/**` endpoints **Then** the Gateway returns 403 Forbidden -- customers cannot access admin endpoints (FR40, NFR17)
3. **Given** a valid JWT with Admin role **When** accessing `/api/v1/admin/**` endpoints **Then** the request is forwarded to the appropriate backend service
4. **Given** public endpoints (`/api/v1/products/**`, `/graphql`) **When** accessed without a JWT **Then** the request is forwarded normally -- no authentication required (FR50)
5. **Given** `/api/v1/cart/**` endpoints **When** accessed without a JWT **Then** the request is allowed with an anonymous cart identifier (anonymous cart supported)
6. **Given** API Gateway route configuration **When** inspected **Then** routes are defined per architecture: products (public), cart (optional auth), orders (Customer required), admin/* (Admin required), graphql (public), auth (public)

## Tasks / Subtasks

- [x] Task 1: Update GatewaySecurityConfig with RBAC rules (AC: 1, 2, 3, 4, 5)
  - [x] 1.1 Modify `GatewaySecurityConfig.java` `authorizeExchange` block -- replace the current `anyExchange().permitAll()` with granular RBAC rules:
    - `/actuator/health/**` -- `permitAll()`
    - `/api/v1/products/**` -- `permitAll()`
    - `/graphql` -- `permitAll()`
    - `/api/v1/cart/**` -- `permitAll()` (anonymous cart supported, auth optional)
    - `/api/v1/orders/**` -- `authenticated()` (Customer or Admin)
    - `/api/v1/admin/**` -- `hasRole("ADMIN")`
    - `anyExchange()` -- `authenticated()` (deny-by-default for unlisted paths)
  - [x] 1.2 Verify that the existing `reactiveJwtConverter()` using `KeycloakRealmRoleConverter` correctly maps `realm_access.roles` to `ROLE_CUSTOMER` and `ROLE_ADMIN` authorities -- this is already wired from Story 3.1, no changes needed to the converter

- [x] Task 2: Add UserIdRelayFilter to propagate authenticated user ID downstream (AC: 1)
  - [x] 2.1 Create `com.robomart.gateway.filter.UserIdRelayFilter.java` -- a `GlobalFilter` that:
    - Checks if the request has an authenticated `Principal` (JWT)
    - Extracts the `sub` claim (Keycloak user UUID) from the JWT
    - Adds/overrides `X-User-Id` header with the JWT `sub` value on the downstream request
    - For unauthenticated requests, passes through the existing `X-User-Id` header from the client (anonymous cart UUID from localStorage)
    - **Security**: Always overwrite `X-User-Id` for authenticated requests to prevent header spoofing -- the frontend-supplied header is only trusted for anonymous users
  - [x] 2.2 Add input validation for anonymous `X-User-Id` header (deferred tech debt from Epic 2):
    - Reject headers with control characters, newlines, or non-printable chars (these are used as Redis keys downstream)
    - Enforce max length of 128 characters
    - If invalid, strip the header (treat as new anonymous user) rather than rejecting the request
  - [x] 2.3 Set filter order to run AFTER Spring Security authentication (`Ordered.LOWEST_PRECEDENCE - 1` or `SecurityWebFiltersOrder.LAST`)

- [x] Task 3: Add placeholder routes for future services (AC: 6)
  - [x] 3.1 Add route entries to `RouteConfig.java` for paths that will be served by future services. These routes exist so RBAC rules can be tested and so the gateway responds with proper 503 (no backend) rather than 404:
    - `/api/v1/orders/**` → `order-service` URI (configurable, default `http://localhost:8083`)
    - `/api/v1/admin/products/**` → `product-service` URI (admin product CRUD via same service)
    - `/api/v1/admin/orders/**` → `order-service` URI
    - `/api/v1/admin/inventory/**` → `inventory-service` URI (configurable, default `http://localhost:8084`)
  - [x] 3.2 Add corresponding service URI properties to `application.yml` under `gateway.services`:
    - `order-service: http://localhost:8083`
    - `inventory-service: http://localhost:8084`
  - [x] 3.3 Do NOT add routes for `/auth/**` -- Keycloak is accessed directly by the frontend via `oidc-client-ts` (Authorization Code + PKCE flow), not through the gateway

- [x] Task 4: Write comprehensive RBAC tests (AC: 1, 2, 3, 4, 5, 6)
  - [x] 4.1 Create `GatewaySecurityRbacTest.java` -- integration test class with `@SpringBootTest(webEnvironment = RANDOM_PORT)`, mock `ReactiveJwtDecoder` (same pattern as existing `ApiGatewayApplicationTests`)
  - [x] 4.2 Create a test JWT helper -- utility method that builds a mock `Jwt` with configurable `sub`, `realm_access.roles`, and `exp` claims. Configure `ReactiveJwtDecoder` mock to return this JWT when given a specific token string
  - [x] 4.3 Test scenarios:
    - **Public endpoints (no token)**: GET `/api/v1/products/1` → 200 (or 503 if no backend, but NOT 401). GET `/graphql` → 200/503. GET `/api/v1/cart/abc` → 200/503
    - **Public endpoints (with valid token)**: GET `/api/v1/products/1` with Bearer → 200/503 (still allowed)
    - **Protected endpoint without token**: GET `/api/v1/orders/1` without Bearer → 401
    - **Protected endpoint with invalid token**: GET `/api/v1/orders/1` with invalid Bearer → 401
    - **Protected endpoint with valid Customer token**: GET `/api/v1/orders/1` with Customer JWT → 200/503
    - **Admin endpoint with Customer token**: GET `/api/v1/admin/products` with Customer JWT → 403
    - **Admin endpoint with Admin token**: GET `/api/v1/admin/products` with Admin JWT → 200/503
    - **Admin endpoint without token**: GET `/api/v1/admin/products` without Bearer → 401
    - **Unknown path with token**: GET `/api/v1/unknown` with valid JWT → 200/503 (authenticated, passes deny-by-default)
    - **Unknown path without token**: GET `/api/v1/unknown` without JWT → 401 (deny-by-default)
  - [x] 4.4 Create `UserIdRelayFilterTest.java` -- test that:
    - Authenticated request has `X-User-Id` overwritten with JWT `sub`
    - Unauthenticated request passes through original `X-User-Id` header
    - Authenticated request ignores client-supplied `X-User-Id` (anti-spoofing)
    - Anonymous `X-User-Id` with control characters is stripped (sanitization)
    - Anonymous `X-User-Id` exceeding 128 chars is stripped (max length)
    - Valid anonymous UUID format passes through unchanged
  - [x] 4.5 Update existing `ApiGatewayApplicationTests` -- ensure existing tests still pass with the new RBAC rules (health endpoint and CORS are public)

### Review Findings

- [x] [Review][Patch] `jwt.getSubject()` may return null — add null guard in `UserIdRelayFilter.withAuthenticatedUserId()` [UserIdRelayFilter.java:35] — FIXED
- [x] [Review][Defer] No rate limiting on cart endpoints — deferred, pre-existing from Epic 2
- [x] [Review][Defer] GraphQL endpoint may bypass path-based RBAC for future mutations — deferred, architectural concern

## Senior Developer Review (AI)

**Review Date:** 2026-03-30
**Review Outcome:** Changes Requested
**Reviewer Model:** Claude Opus 4.6

**Review Layers:** Blind Hunter, Edge Case Hunter, Acceptance Auditor (all completed)

**Summary:** 1 patch, 2 deferred, 25 dismissed as noise/by-design/pre-existing

### Action Items

- [x] [High] Add null check for `jwt.getSubject()` in `UserIdRelayFilter.withAuthenticatedUserId()` to prevent NPE if JWT `sub` claim is absent — FIXED

## Dev Notes

### Critical: Existing Infrastructure (DO NOT Recreate)

Story 3.1 already built the complete security foundation. Reuse everything:

- **`GatewaySecurityConfig.java`** (`backend/api-gateway/src/main/java/com/robomart/gateway/config/`) -- JWT + ReactiveJwtAuthenticationConverterAdapter already wired. ONLY modify `authorizeExchange` block and remove the `anyExchange().permitAll()` comment
- **`KeycloakRealmRoleConverter`** (`backend/security-lib/`) -- Already maps `realm_access.roles` → `ROLE_CUSTOMER`/`ROLE_ADMIN`. No changes needed
- **`AuthContext`** (`backend/security-lib/`) -- Utility for `getUserId()`, `getEmail()`, `getRoles()`, `hasRole()`. Available for downstream services. No changes needed in this story
- **`RoleConstants`** (`backend/security-lib/`) -- `ROLE_CUSTOMER`, `ROLE_ADMIN`, `CUSTOMER`, `ADMIN`. Use these constants in tests
- **`RouteConfig.java`** (`backend/api-gateway/`) -- Has product, graphql, cart routes. Extend with new routes

### Critical: Reactive Gateway (WebFlux, NOT Servlet)

API Gateway uses **Spring Cloud Gateway (WebFlux)**. All security uses the reactive stack:
- `SecurityWebFilterChain` (NOT `SecurityFilterChain`)
- `ServerHttpSecurity` (NOT `HttpSecurity`)
- `ReactiveJwtDecoder` (NOT `JwtDecoder`)
- `GlobalFilter` / `GatewayFilter` (NOT `OncePerRequestFilter`)
- `ServerWebExchange` (NOT `HttpServletRequest`)
- `Mono`/`Flux` reactive types

### Critical: `hasRole()` vs `hasAuthority()` in Spring Security

`KeycloakRealmRoleConverter` produces authorities with `ROLE_` prefix (e.g., `ROLE_ADMIN`). Spring Security's `hasRole("ADMIN")` automatically prepends `ROLE_`, so:
- Use `hasRole("ADMIN")` -- Spring checks for `ROLE_ADMIN` ✅
- Do NOT use `hasRole("ROLE_ADMIN")` -- would check for `ROLE_ROLE_ADMIN` ❌
- Or use `hasAuthority("ROLE_ADMIN")` -- checks exact string ✅

### Critical: Cart Endpoint Auth Strategy

`/api/v1/cart/**` remains `permitAll()` because:
- Anonymous users add to cart via `X-User-Id` header (localStorage UUID) -- established in Epic 2
- Authenticated users use JWT `sub` as `X-User-Id` -- established in Story 3.2
- Story 3.4 implements cart merge on login
- The `UserIdRelayFilter` bridges this: authenticated → JWT sub, anonymous → passthrough header

### Critical: Order Endpoint Auth

`/api/v1/orders/**` uses `.authenticated()` (not `.hasRole("CUSTOMER")`) because:
- Both Customer and Admin roles should access orders (Admins manage orders in Epic 5)
- The backend Order Service (Epic 4) will have finer-grained authorization via `@PreAuthorize`

### Route Strategy for Non-Existent Services

Order Service, Inventory Service don't exist yet (Epic 4). Adding routes now means:
- Gateway RBAC can be tested end-to-end (auth rules apply before routing)
- Requests that pass auth will get `502 Bad Gateway` (no upstream) -- this is correct behavior, NOT a bug
- When Epic 4 creates these services, the routes are already in place
- Do NOT create dummy/mock services -- let the gateway's natural 502 response verify routing works

### Test Pattern (from Story 3.1)

Existing test setup in `ApiGatewayApplicationTests`:
```java
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri="
)
class ApiGatewayApplicationTests {
    @MockitoBean ReactiveJwtDecoder jwtDecoder;
    @LocalServerPort int port;
    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();
    }
}
```

For RBAC tests, use the same pattern. To simulate a valid JWT:
```java
Jwt jwt = Jwt.withTokenValue("mock-token")
    .header("alg", "RS256")
    .claim("sub", "user-uuid")
    .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
    .build();
when(jwtDecoder.decode("mock-token")).thenReturn(Mono.just(jwt));

webTestClient.get().uri("/api/v1/orders/1")
    .headers(h -> h.setBearerAuth("mock-token"))
    .exchange()
    .expectStatus().isOk(); // or 502 if no backend
```

### Expected RBAC Matrix

| Path Pattern | No Token | Customer Token | Admin Token |
|---|---|---|---|
| `/actuator/health/**` | 200 ✅ | 200 ✅ | 200 ✅ |
| `/api/v1/products/**` | 200/503 ✅ | 200/503 ✅ | 200/503 ✅ |
| `/graphql` | 200/503 ✅ | 200/503 ✅ | 200/503 ✅ |
| `/api/v1/cart/**` | 200/503 ✅ | 200/503 ✅ | 200/503 ✅ |
| `/api/v1/orders/**` | 401 ❌ | 200/502 ✅ | 200/502 ✅ |
| `/api/v1/admin/**` | 401 ❌ | 403 ❌ | 200/502 ✅ |
| Any other path | 401 ❌ | 200/502 ✅ | 200/502 ✅ |

### Project Structure Notes

```
backend/api-gateway/
├── pom.xml                                          # NO CHANGES needed (deps already present)
└── src/
    ├── main/
    │   ├── java/com/robomart/gateway/
    │   │   ├── ApiGatewayApplication.java           # NO CHANGES
    │   │   ├── config/
    │   │   │   ├── GatewaySecurityConfig.java       # MODIFY: tighten authorizeExchange rules
    │   │   │   ├── RouteConfig.java                 # MODIFY: add order/admin routes
    │   │   │   └── CorsConfig.java                  # NO CHANGES
    │   │   └── filter/
    │   │       └── UserIdRelayFilter.java           # NEW: propagate JWT sub as X-User-Id
    │   └── resources/
    │       └── application.yml                      # MODIFY: add order-service, inventory-service URIs
    └── test/
        └── java/com/robomart/gateway/
            ├── ApiGatewayApplicationTests.java      # VERIFY existing tests still pass
            ├── GatewaySecurityRbacTest.java          # NEW: comprehensive RBAC test suite
            └── filter/
                └── UserIdRelayFilterTest.java        # NEW: filter unit tests
```

### Testing Strategy

**Integration tests** (WebTestClient + mocked ReactiveJwtDecoder):
- Test full request flow through gateway security chain
- Mock `ReactiveJwtDecoder` to return controlled JWTs
- Verify HTTP status codes for all RBAC combinations
- Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` consistent with project convention

**Unit tests** (UserIdRelayFilter):
- Mock `ServerWebExchange`, `Principal`, `Jwt`
- Verify header manipulation logic in isolation

**Test naming convention**: `should{Expected}When{Condition}` (project convention from Epic 2)

### Dependencies

- **Story 3.1 (DONE)**: Keycloak, API Gateway, security-lib -- all infrastructure in place
- **Story 3.2 (DONE)**: Frontend sends `Authorization: Bearer` header via Axios interceptor, `X-User-Id` with JWT `sub` when authenticated
- **No new Maven dependencies needed** -- all required deps already in `api-gateway/pom.xml`

### Previous Story Intelligence

**From Story 3.1:**
- `@MockitoBean` (NOT `@MockBean`) for Spring Boot 4
- `WebTestClient.bindToServer()` + `@LocalServerPort` (NOT auto-configured)
- `spring.security.oauth2.resourceserver.jwt.issuer-uri=` (empty) in test properties to skip OIDC discovery
- API Gateway scans `com.robomart` base package (picks up security-lib beans automatically)

**From Story 3.2:**
- Frontend Axios interceptor attaches `Authorization: Bearer <accessToken>` on authenticated requests
- `X-User-Id` header sent with JWT `sub` claim when authenticated, or localStorage UUID when anonymous
- `oidc-client-ts` manages token lifecycle -- gateway doesn't need to handle refresh

**From Epic 2 Retro:**
- Input validation at API Gateway is a tracked tech debt item (deferred-work.md) -- not in scope for this story but aware
- X-User-Id header pattern bridges anonymous → authenticated identity
- Race condition awareness -- not relevant for gateway RBAC but noted

### References

- [Source: _bmad-output/planning-artifacts/epics.md -- Epic 3, Story 3.3 (Lines 807-837)]
- [Source: _bmad-output/planning-artifacts/architecture.md -- API Gateway & Security sections]
- [Source: _bmad-output/planning-artifacts/prd.md -- FR37-FR42 Identity & Access, FR50 API Gateway]
- [Source: _bmad-output/implementation-artifacts/3-1-add-keycloak-api-gateway-infrastructure.md -- Dev Notes, File List]
- [Source: _bmad-output/implementation-artifacts/3-2-implement-customer-registration-login.md -- X-User-Id transition]
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-03-29.md -- Tech Debt, Deferred Items]
- [Source: backend/api-gateway/src/main/java/com/robomart/gateway/config/GatewaySecurityConfig.java -- Current state]
- [Source: backend/security-lib/src/main/java/com/robomart/security/converter/KeycloakRealmRoleConverter.java]

## Dev Agent Record

### Agent Model Used
Claude Opus 4.6

### Debug Log References
- Initial test compilation: `assert` keyword not usable inside lambda → switched to AssertJ `assertThat`
- Backend services not running → gateway returns 500 (connection refused) instead of 502/503 → used `assertThat(status).isNotIn(401, 403)` pattern to verify auth isn't blocking
- `WebTestClient` rejects control characters in headers (HTTP spec) → tested control char validation via direct unit test on `isValidAnonymousUserId()`
- Invalid token mock must use `BadJwtException` (not `RuntimeException`) for Spring Security to return 401

### Completion Notes List
- Tightened GatewaySecurityConfig RBAC: public (products, graphql, cart, health), authenticated (orders), hasRole ADMIN (admin/**), deny-by-default
- Created UserIdRelayFilter GlobalFilter: JWT sub overwrite for authenticated, passthrough for anonymous, input validation (control chars, max 128 length)
- Added placeholder routes for order-service, admin-products, admin-orders, admin-inventory with configurable URIs
- 27 total tests passing: 17 RBAC integration tests, 7 UserIdRelayFilter tests, 3 existing tests (regression-free)

### Change Log
- 2026-03-30: Implemented Story 3.3 - API Gateway JWT Validation & RBAC with 4 tasks completed

### File List
- backend/api-gateway/src/main/java/com/robomart/gateway/config/GatewaySecurityConfig.java (MODIFIED)
- backend/api-gateway/src/main/java/com/robomart/gateway/config/RouteConfig.java (MODIFIED)
- backend/api-gateway/src/main/java/com/robomart/gateway/filter/UserIdRelayFilter.java (NEW)
- backend/api-gateway/src/main/resources/application.yml (MODIFIED)
- backend/api-gateway/src/test/java/com/robomart/gateway/GatewaySecurityRbacTest.java (NEW)
- backend/api-gateway/src/test/java/com/robomart/gateway/filter/UserIdRelayFilterTest.java (NEW)
