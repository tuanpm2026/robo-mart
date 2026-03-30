# Story 3.1: Add Keycloak & API Gateway Infrastructure

Status: done

## Story

As a developer,
I want Keycloak and API Gateway running locally with pre-configured realm, roles, and demo users,
So that authentication and authorization can be developed and tested.

## Acceptance Criteria

1. **Given** Docker Compose **When** updated for this story **Then** Keycloak container with PostgreSQL (keycloak_db) is added to core profile (total: 8 containers)
2. **Given** Keycloak **When** started with the realm export (`infra/docker/keycloak/robomart-realm.json`) **Then** a "robomart" realm is configured with: Customer and Admin roles, email/password login enabled, Google and GitHub identity providers configured (client IDs can be placeholder), JWT access token TTL 15 minutes, refresh token TTL 24 hours
3. **Given** the Keycloak realm **When** demo profile is active **Then** two demo users exist: `demo-customer@robomart.com` (Customer role) and `demo-admin@robomart.com` (Admin role)
4. **Given** API Gateway module (`api-gateway`) **When** created with Spring Cloud Gateway **Then** it includes: RouteConfig (routes for `/api/v1/products/**`, `/api/v1/cart/**`, with more routes added by later epics), CorsConfig (allowing both frontend origins), and basic request forwarding to Product Service and Cart Service
5. **Given** `security-lib` module **When** implemented for this story **Then** it provides: KeycloakRealmRoleConverter (extract roles from JWT `realm_access.roles`), SecurityConfig (@Configuration exposing converter as @Bean), AuthContext (extract user ID and roles from SecurityContext), RoleConstants (ROLE_CUSTOMER, ROLE_ADMIN). Note: Spring Security's built-in OAuth2 resource server handles JWT validation — no custom JwtAuthenticationFilter needed.

## Tasks / Subtasks

- [ ] Task 1: Add Keycloak + keycloak_db to Docker Compose (AC: 1)
  - [ ] 1.1 Add `keycloak-db` PostgreSQL 17 container (separate from product_db) with health check
  - [ ] 1.2 Add Keycloak 26.x container with `start-dev --import-realm`, port 8180, health check
  - [ ] 1.3 Add env vars to `.env` / `.env.example` (`KEYCLOAK_PORT`, `KEYCLOAK_DB_*`, `KC_BOOTSTRAP_ADMIN_*`)
  - [ ] 1.4 Verify total container count = 8 in core profile
- [ ] Task 2: Create Keycloak realm export JSON (AC: 2, 3)
  - [ ] 2.1 Create `infra/docker/keycloak/robomart-realm.json` with realm "robomart"
  - [ ] 2.2 Configure realm roles: `CUSTOMER`, `ADMIN`
  - [ ] 2.3 Configure clients: `robo-mart-frontend` (public, PKCE), `robo-mart-gateway` (confidential)
  - [ ] 2.4 Configure Google + GitHub identity providers with placeholder client IDs
  - [ ] 2.5 Set token lifespans: access=900s (15min), refresh=86400s (24h)
  - [ ] 2.6 Add demo users: `demo-customer@robomart.com` (CUSTOMER), `demo-admin@robomart.com` (ADMIN)
- [ ] Task 3: Implement `security-lib` module (AC: 5)
  - [ ] 3.1 Add dependencies to `security-lib/pom.xml`: `spring-security-oauth2-resource-server`, `spring-security-oauth2-jose` (core libs only, NO web framework starters). Add `spring-security-test` for test scope
  - [ ] 3.2 Create `RoleConstants.java` — `ROLE_CUSTOMER`, `ROLE_ADMIN`
  - [ ] 3.3 Create `KeycloakRealmRoleConverter.java` — extract roles from JWT `realm_access.roles` claim, prefix with `ROLE_`
  - [ ] 3.4 Create `SecurityConfig.java` — @Configuration exposing `KeycloakRealmRoleConverter` as @Bean (NO SecurityFilterChain/SecurityWebFilterChain — each consumer module creates its own)
  - [ ] 3.5 Create `AuthContext.java` — extract userId (`sub` claim) and roles from SecurityContext (works in both servlet and reactive contexts)
  - [ ] 3.6 Write unit tests for `KeycloakRealmRoleConverter` and `AuthContext` using mock JWT tokens
- [ ] Task 4: Create API Gateway module (AC: 4)
  - [ ] 4.1 Create `api-gateway` Maven module, add to parent POM modules list
  - [ ] 4.2 Add dependencies: `spring-cloud-starter-gateway`, `spring-boot-starter-security-oauth2-resource-server`, `security-lib`
  - [ ] 4.3 Create `GatewaySecurityConfig.java` — reactive security with `@EnableWebFluxSecurity`, `ServerHttpSecurity`, `SecurityWebFilterChain`. Wire `KeycloakRealmRoleConverter` from security-lib via `ReactiveJwtAuthenticationConverterAdapter`. Configure public routes (products, graphql) as permitAll
  - [ ] 4.4 Create `RouteConfig.java` — routes for products (public) and cart (optional auth)
  - [ ] 4.5 Create `CorsConfig.java` — allow both frontend origins (5173 customer, 5174 admin) + Keycloak origin (8180)
  - [ ] 4.6 Create `application.yml` with JWT issuer-uri, route definitions, CORS config, `DedupeResponseHeader` default filter
  - [ ] 4.7 Create main `ApiGatewayApplication.java`
  - [ ] 4.8 Write integration test: gateway starts, routes forward to services, CORS headers present
- [ ] Task 5: Verify full stack integration
  - [ ] 5.1 `docker compose --profile core up` starts all 8 containers with health checks passing
  - [ ] 5.2 Keycloak admin console accessible at `http://localhost:8180`
  - [ ] 5.3 Realm "robomart" exists with roles, clients, and demo users
  - [ ] 5.4 API Gateway routes requests to Product Service and Cart Service
  - [ ] 5.5 Existing tests still pass (zero regressions)

## Dev Notes

### Critical: Spring Boot 4 + Spring Cloud Gateway is REACTIVE

Spring Cloud Gateway 5.0.x (bundled with Spring Cloud 2025.1.1 Oakwood) is built on **WebFlux**. This means:
- Use `ServerHttpSecurity` + `SecurityWebFilterChain` (NOT `HttpSecurity` / `SecurityFilterChain`)
- Use `@EnableWebFluxSecurity` (NOT `@EnableWebSecurity`)
- Use `ReactiveJwtAuthenticationConverterAdapter` to wrap servlet JWT converters
- Do NOT include `spring-boot-starter-web` / `spring-boot-starter-webmvc` in API Gateway — it conflicts with WebFlux

`security-lib` is shared by both servlet services and reactive gateway. Keep security-lib as a **library with no Spring Boot starters** — each consumer (service or gateway) brings its own web framework.

### Critical: Spring Boot 4 Renamed OAuth2 Starters

Spring Boot 4 deprecated old names. Use the new names:

| Old (deprecated) | New (Spring Boot 4) |
|---|---|
| `spring-boot-starter-oauth2-resource-server` | `spring-boot-starter-security-oauth2-resource-server` |
| `spring-boot-starter-oauth2-client` | `spring-boot-starter-security-oauth2-client` |

Both are managed by Spring Boot BOM — no version needed.

### Critical: Keycloak 26.x Docker Changes

- Admin env vars renamed: `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` (NOT old `KEYCLOAK_ADMIN`)
- Realm import: `--import-realm` flag + mount JSON to `/opt/keycloak/data/import/`
- Health check: `GET /health/ready` on same port
- Dev mode: `start-dev` (HTTP enabled, no hostname verification)

### Critical: Keycloak JWT Role Extraction

Keycloak embeds roles in a nested `realm_access.roles` claim, NOT at the top level. Spring Security does NOT auto-map these. A custom `KeycloakRealmRoleConverter` is required:

```java
// JWT claim structure from Keycloak:
// { "realm_access": { "roles": ["CUSTOMER", "ADMIN"] }, "sub": "user-uuid", ... }
// Must be converted to: ROLE_CUSTOMER, ROLE_ADMIN GrantedAuthorities
```

### API Gateway Route Architecture

Per architecture doc, routes for this story:

| Route Pattern | Target Service | Auth Required | Port |
|---|---|---|---|
| `/api/v1/products/**` | Product Service | No | 8081 |
| `/api/v1/cart/**` | Cart Service | No (optional) | 8082 |
| `/graphql` | Product Service | No | 8081 |

Future stories (3.3+) will add:
- `/api/v1/orders/**` → Order Service (CUSTOMER role)
- `/api/v1/admin/**` → Various services (ADMIN role)
- `/api/v1/auth/**` → Keycloak (public)

### CORS Configuration Gotcha

For Spring Cloud Gateway CORS:
- Set `spring.cloud.gateway.globalcors.add-to-simple-url-handler-mapping: true` — without this, CORS preflight OPTIONS requests that don't match route predicates get 403
- Add `DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST` as default filter — prevents duplicate CORS headers when both gateway and downstream services add them

### Keycloak Realm Clients

Two OAuth2 clients needed:

1. **`robo-mart-frontend`** — Public client (SPA)
   - `publicClient: true`
   - PKCE with S256 required
   - Redirect URIs: `http://localhost:5173/*`, `http://localhost:5174/*`
   - Web origins: `http://localhost:5173`, `http://localhost:5174`

2. **`robo-mart-gateway`** — Confidential client (server-side)
   - `publicClient: false`
   - Secret: configurable via env var
   - Redirect URIs: `http://localhost:8080/*`
   - For future TokenRelay pattern

### Token Lifespans (per NFR13)

- Access token: **900 seconds** (15 minutes)
- Refresh token: **86400 seconds** (24 hours)
- SSO Session Idle: 1800 seconds (30 minutes)
- SSO Session Max: 86400 seconds (24 hours)

### Identity Provider Placeholders

Google and GitHub identity providers in the realm export use placeholder client IDs/secrets. They will be configured with real credentials when deploying to staging. Structure:

```json
{
  "alias": "google",
  "providerId": "google",
  "enabled": true,
  "config": {
    "clientId": "GOOGLE_CLIENT_ID_PLACEHOLDER",
    "clientSecret": "GOOGLE_CLIENT_SECRET_PLACEHOLDER"
  }
}
```

### Existing Infrastructure Context

Current Docker Compose has 6 containers in core profile:
1. PostgreSQL 17 (product_db) — port 5432
2. Elasticsearch 8.17.0 — port 9200
3. Kafka 7.9.0 (KRaft) — port 29092
4. Schema Registry 7.9.0 — port 8085
5. Redis 7 alpine — port 6379
6. Kafka UI 0.7.2 — port 9090

This story adds 2 more:
7. **keycloak-db** (PostgreSQL 17) — port 5433
8. **Keycloak 26.x** — port 8180

### Input Validation Note (from Epic 2 deferred work)

Epic 2 deferred 2 input validation items (userId/cartId header sanitization, max length) to "Epic 3 API Gateway." These will be addressed in Story 3.3 (JWT Validation & RBAC), NOT in this infrastructure story.

### Project Structure Notes

New files and modules to create:

```
backend/
├── api-gateway/                          # NEW MODULE
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/robomart/gateway/
│       │   │   ├── ApiGatewayApplication.java
│       │   │   └── config/
│       │   │       ├── GatewaySecurityConfig.java  # Reactive: @EnableWebFluxSecurity + SecurityWebFilterChain
│       │   │       ├── RouteConfig.java
│       │   │       └── CorsConfig.java
│       │   └── resources/
│       │       └── application.yml
│       └── test/
│           └── java/com/robomart/gateway/
│               └── ApiGatewayApplicationTests.java
├── security-lib/                          # EXISTING (skeleton → implement)
│   ├── pom.xml                            # ADD spring-security-oauth2-resource-server, spring-security-oauth2-jose
│   └── src/
│       ├── main/java/com/robomart/security/
│       │   ├── config/
│       │   │   └── SecurityConfig.java        # @Configuration with @Bean KeycloakRealmRoleConverter (NO filter chain)
│       │   ├── converter/
│       │   │   └── KeycloakRealmRoleConverter.java
│       │   └── util/
│       │       ├── AuthContext.java
│       │       └── RoleConstants.java
│       └── test/java/com/robomart/security/
│           ├── converter/
│           │   └── KeycloakRealmRoleConverterTest.java
│           └── util/
│               └── AuthContextTest.java
infra/docker/
├── docker-compose.yml                     # UPDATE (add keycloak + keycloak-db)
├── .env                                   # UPDATE (add KEYCLOAK_* vars)
├── .env.example                           # UPDATE
└── keycloak/                              # NEW DIRECTORY
    └── robomart-realm.json
```

### security-lib Design Constraints

`security-lib` is consumed by BOTH servlet services (Product, Cart) AND reactive Gateway. To avoid framework conflicts:
- Do NOT include `spring-boot-starter-web` or `spring-cloud-starter-gateway` in security-lib
- Only include: `spring-security-oauth2-resource-server`, `spring-security-oauth2-jose` (core libraries, no web framework)
- Each consumer module declares its own web framework dependency
- `SecurityConfig.java` in security-lib provides **component beans** (converter, role extractor) but NOT `SecurityFilterChain` / `SecurityWebFilterChain` — those belong in each service's own config

Revised approach for security-lib:
```
security-lib provides:
  - KeycloakRealmRoleConverter (framework-agnostic)
  - AuthContext (extracts from SecurityContext — works in both servlet/reactive)
  - RoleConstants (plain constants)

Each service provides its own:
  - SecurityFilterChain (servlet) or SecurityWebFilterChain (reactive)
  - Wires in KeycloakRealmRoleConverter from security-lib
```

### Testing Strategy

**Unit tests** (security-lib):
- `KeycloakRealmRoleConverterTest` — verify realm_access.roles extraction, empty roles, missing claim
- `AuthContextTest` — verify userId and roles extraction from SecurityContext
- Use mock JWT tokens (no Keycloak container needed)
- Test naming: `should{Expected}When{Condition}()`
- AssertJ for assertions

**Integration tests** (api-gateway):
- Gateway application starts successfully
- Route forwarding to downstream services
- CORS headers present on preflight requests
- Use `@SpringBootTest` with `WebEnvironment.RANDOM_PORT`
- Mock downstream services or use WireMock

**Smoke tests** (manual verification):
- `docker compose --profile core up` — all 8 containers healthy
- Keycloak admin console at `http://localhost:8180` — realm, users, clients exist
- Gateway routes: `http://localhost:8080/api/v1/products` → product-service response

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Epic 3, Story 3.1]
- [Source: _bmad-output/planning-artifacts/architecture.md — Authentication & Security (Lines 345-363)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Project Directory Structure (Lines 1090-1175)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Route Protection Table (Lines 1728-1734)]
- [Source: _bmad-output/planning-artifacts/prd.md — FR37-FR42 Identity & Access]
- [Source: _bmad-output/planning-artifacts/prd.md — NFR11-NFR17 Security Requirements]
- [Source: _bmad-output/planning-artifacts/prd.md — NFR13 Token Lifespans]
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-03-29.md — Epic 3 Preview & Risks]
- [Source: _bmad-output/implementation-artifacts/deferred-work.md — Input validation deferred to Epic 3]
- [Source: Keycloak 26.x Docker documentation]
- [Source: Spring Cloud Gateway 5.0.x documentation]
- [Source: Spring Security 7 migration guide]

### Previous Epic Intelligence

**From Epic 2 Retrospective:**
- Pre-auth identity resolution via `X-User-Id` / `X-Cart-Id` headers works — bridges anonymous → authenticated (Story 3.4 will handle merge)
- Jackson 3.x (`tools.jackson.databind`) confirmed throughout — if any JWT claim serialization needed, use Jackson 3.x APIs
- Kafka consumer patterns established — reusable for auth event consumers if needed
- Docker Compose pattern: all containers use core profile, health checks, memory limits, named volumes
- New containers MUST include health checks (team agreement from Epic 2)
- Input validation deferred from Epic 2: `X-User-Id` header sanitization and max length — target Story 3.3

**From Epic 2 Action Items:**
- Research Spring Boot 4 + Keycloak integration (completed in this story's research phase)
- Race condition awareness — not relevant for this infrastructure story
- Continue 3-layer code review process

### Key Risks (from Epic 2 Retro)

1. **CORS for Keycloak redirects** — Gateway CORS must allow Keycloak origin for OAuth2 redirect flows
2. **Token storage strategy** — Will be decided in Story 3.2 (frontend). This story only sets up infrastructure
3. **Keycloak startup time** — Keycloak can take 30-60s to start. Ensure health check has adequate `start_period`

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6

### Debug Log References

### Completion Notes List

- Spring Cloud Gateway starter renamed in 2025.1.1: `spring-cloud-starter-gateway-server-webflux` (not `spring-cloud-starter-gateway`)
- Spring Boot 4 OAuth2 starters renamed: `spring-boot-starter-security-oauth2-resource-server`
- WebTestClient not auto-configured in Spring Boot 4 tests — use `WebTestClient.bindToServer()` with `@LocalServerPort`
- `@MockitoBean` replaces deprecated `@MockBean` in Spring Boot 4 / Spring Framework 7
- SLF4J not transitively available from `spring-security-oauth2-resource-server` — add explicit `slf4j-api` dependency
- Keycloak 26.x: env vars renamed to `KC_BOOTSTRAP_ADMIN_USERNAME`/`KC_BOOTSTRAP_ADMIN_PASSWORD`
- Code review: 16 findings (0 critical, 4 high, 7 medium, 5 low) — all HIGH and MEDIUM patched

### File List

**New files:**
- `infra/docker/keycloak/robomart-realm.json`
- `backend/api-gateway/pom.xml`
- `backend/api-gateway/src/main/java/com/robomart/gateway/ApiGatewayApplication.java`
- `backend/api-gateway/src/main/java/com/robomart/gateway/config/GatewaySecurityConfig.java`
- `backend/api-gateway/src/main/java/com/robomart/gateway/config/RouteConfig.java`
- `backend/api-gateway/src/main/java/com/robomart/gateway/config/CorsConfig.java`
- `backend/api-gateway/src/main/resources/application.yml`
- `backend/api-gateway/src/test/java/com/robomart/gateway/ApiGatewayApplicationTests.java`
- `backend/security-lib/src/main/java/com/robomart/security/util/RoleConstants.java`
- `backend/security-lib/src/main/java/com/robomart/security/converter/KeycloakRealmRoleConverter.java`
- `backend/security-lib/src/main/java/com/robomart/security/config/SecurityConfig.java`
- `backend/security-lib/src/main/java/com/robomart/security/util/AuthContext.java`
- `backend/security-lib/src/test/java/com/robomart/security/converter/KeycloakRealmRoleConverterTest.java`
- `backend/security-lib/src/test/java/com/robomart/security/util/AuthContextTest.java`

**Modified files:**
- `infra/docker/docker-compose.yml` (added keycloak-db, keycloak, kafka-ui healthcheck)
- `infra/docker/.env` (added keycloak env vars)
- `infra/docker/.env.example` (added keycloak env vars)
- `backend/pom.xml` (added api-gateway module)
- `backend/security-lib/pom.xml` (added security dependencies)
