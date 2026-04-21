# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

**Backend (Maven multimodule — run from `backend/`):**
```bash
./mvnw clean install              # Build all services (includes tests)
./mvnw clean install -DskipTests  # Skip tests
./mvnw clean install -T 1C        # Parallel build
./mvnw spring-boot:run -pl :product-service  # Run a single service
```

**Frontend (npm workspaces — run from `frontend/`):**
```bash
npm install
npm run dev       # Starts all apps with hot-reload
npm run build     # Production build
npm run lint      # oxlint + ESLint
npm run format    # Prettier
npm run type-check
```

**Git hooks (run once after clone):**
```bash
frontend/setup-hooks.sh   # Installs pre-push hook → format, lint, type-check
```

**Infrastructure:**
```bash
cd infra/docker && docker-compose --profile core up -d   # Start all infra
```

## Testing

**Backend:**
```bash
# All tests in a service
./mvnw test -pl :product-service

# Single test class
./mvnw test -pl :product-service -Dtest=ProductServiceCacheTest

# Single test method
./mvnw test -pl :product-service -Dtest=ProductServiceCacheTest#methodName
```

**Frontend:**
```bash
cd frontend/customer-website && npm run test:unit
cd frontend/admin-dashboard && npm run test:unit
```

**Test patterns:** Unit tests live in `src/test/java/.../unit/`. Integration tests use Testcontainers (PostgreSQL, Kafka, Elasticsearch). Base test classes and container configs are in `backend/test-support/`.

## Checkstyle
```bash
cd backend && ./mvnw checkstyle:check
```
Rules are in `backend/config/checkstyle/checkstyle.xml`. Checkstyle runs automatically on `compile`.

## Architecture Overview

**Monorepo layout:** `backend/` (Java microservices) + `frontend/` (Vue 3 apps) + `infra/` (Docker, K8s).

**Shared backend modules (depended on by services):**
- `common-lib` — DTOs, exceptions, shared config, structured logging utilities
- `security-lib` — Keycloak JWT converter (`KeycloakRealmRoleConverter`), auth context; JWT roles come from `realm_access.roles` (nested claim)
- `proto` — gRPC `.proto` definitions + generated stubs (Order/Inventory/Payment sync calls)
- `events` — Kafka event schemas (Avro); generated via `avro-maven-plugin`; Schema Registry at `localhost:8085`
- `test-support` — Testcontainers setup, reusable base test classes

**Service communication:**
- Sync (gateway → service, service → service): REST via API Gateway (port 8080) with JWT validation + RBAC
- Async (service → service): Kafka with Avro schemas; consumers in each service's `event/consumer/` package, producers in `event/producer/`
- gRPC: Order service orchestrates inventory/payment via gRPC (proto-defined contracts)

**Data patterns:**
- Each service owns its own PostgreSQL instance (ports 5432–5437); Flyway migrations in `src/main/resources/db/migration/`
- Product Service: Elasticsearch (port 9200) for search + Redis (port 6379) for cache + outbox table for transactional events
- Cart Service: Redis-backed (no DB persistence)
- Inventory Service: Redis distributed locking to prevent oversell
- Order Service: Saga orchestration — coordinates Inventory reserve → Payment charge → emit events; compensates on failure

**Frontend:** Vue 3 + Pinia + PrimeVue + Tailwind + OIDC Client TS (Keycloak SSO). Customer portal on port 5173, admin dashboard on 5174. Shared components in `frontend/shared/` via npm workspaces.

## Critical: Spring Boot 4 + Jackson 3.x

This project uses **Spring Boot 4.0.4** which bundles **Jackson 3.x**:
- Package: `tools.jackson.databind` (NOT `com.fasterxml.jackson.databind`)
- Annotations: still `com.fasterxml.jackson.annotation` (unchanged)
- `Jackson2ObjectMapperBuilderCustomizer` removed → use `JsonMapperBuilderCustomizer` from `org.springframework.boot.jackson.autoconfigure`
- `WRITE_DATES_AS_TIMESTAMPS` removed — ISO dates are Jackson 3.x default; no `jackson-datatype-jsr310` needed
- NON_NULL: `builder.changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))`

## Spring Boot 4 / Spring Framework 7 Gotchas

- Autoconfigure classes moved to module-specific jars; Flyway → use `spring-boot-starter-flyway` (not raw `flyway-core`)
- Tracing needs `spring-boot-micrometer-tracing-brave`
- `TestRestTemplate` removed → use `RestClient` with `@LocalServerPort`
- `@MockBean` deprecated → use `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`)
- `@AutoConfigureMockMvc` / `@WebMvcTest` removed from test-autoconfigure
- `WebTestClient` not auto-configured in `@SpringBootTest` → use `WebTestClient.bindToServer()` + `@LocalServerPort`
- Do NOT ban `commons-logging` in enforcer-plugin (Spring Framework 7 depends on it directly)
- Spring Cloud 2025.1.1: Gateway starter is `spring-cloud-starter-gateway-server-webflux`

## Keycloak 26.x

- Docker image: `quay.io/keycloak/keycloak:26.1.4`
- Admin env vars: `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`
- Realm: `robomart`; JWT roles in `realm_access.roles` (nested) — handled by `KeycloakRealmRoleConverter` in `security-lib`

## Testcontainers 2.x

- Artifact names changed: `postgresql` → `testcontainers-postgresql`, `junit-jupiter` → `testcontainers-junit-jupiter`
- `PostgreSQLContainer` available in both `org.testcontainers.containers` and `org.testcontainers.postgresql`

## Service Ports

| Service | Port |
|---------|------|
| API Gateway | 8080 |
| Product Service | 8081 |
| Cart Service | 8082 |
| Order Service | 8083 |
| Inventory Service | 8084 |
| Schema Registry | 8085 |
| Payment Service | 8086 |
| Keycloak | 8180 |
| Kafka | 29092 |
| Elasticsearch | 9200 |
| Redis | 6379 |
| Kafka UI | 9090 |
| PostgreSQL (product) | 5432 |
| PostgreSQL (keycloak) | 5433 |
| PostgreSQL (order) | 5434 |
| PostgreSQL (inventory) | 5435 |
| PostgreSQL (payment) | 5436 |
| PostgreSQL (notification) | 5437 |
