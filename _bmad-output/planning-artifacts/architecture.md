---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - prd.md
  - prd-validation-report.md
  - ux-design-specification.md
workflowType: 'architecture'
lastStep: 8
status: 'complete'
completedAt: '2026-03-27'
project_name: 'robo-mart'
user_name: 'Mark'
date: '2026-03-27'
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**

74 FRs across 10 domains representing a complete E-commerce platform with distributed system patterns:

- **Customer-facing** (FR1-FR22, FR37-FR42): Product discovery (Elasticsearch-powered search, faceted filtering), cart management (Redis-based, TTL expiry with notification), order processing (Saga-orchestrated across 3 services), payment (mock with idempotency), authentication (Keycloak OAuth2, social login, anonymous cart merge)
- **Admin-facing** (FR24-FR26, FR43-FR49, FR70, FR74): Product CRUD, inventory management with low-stock alerts, order management with status filtering, real-time WebSocket monitoring (order events, inventory alerts, system health), CQRS-powered reporting, DLQ management, product image upload
- **System-level** (FR50-FR72): API Gateway routing, gRPC inter-service communication, Kafka async events, Circuit Breaker, DLQ, Outbox Pattern, graceful degradation (tiered — per-service failure mapped to UX response), graceful shutdown, distributed tracing, correlation ID propagation, health checks, rate limiting, centralized configuration, Flyway migrations, caching with event-driven invalidation, API response aggregation, event sourcing, reconciliation jobs, audit trail

**Requirement Density Distribution:**

Order Processing + System Resilience chiếm ~40% tổng FRs — **Order domain là trung tâm kiến trúc**. Mọi quyết định architecture nên được evaluate qua lens "does this support the order flow?".

**Orphan Requirements Note:** PRD Validation flagged 12 orphan FRs. Most critical: Order Cancellation (FR20-FR22) defines Saga compensation + race condition handling nhưng không có supporting User Journey. Architecture decisions cho Saga compensation phải cover cả cancel path, không chỉ happy path.

**Non-Functional Requirements:**

62 NFRs that will directly shape architectural decisions:

- **Performance** (NFR1-10): API p95 < 200ms, search p95 < 500ms, Saga completion < 3s, gRPC p95 < 50ms, WebSocket delivery < 1s, 100 concurrent orders without corruption, Kafka lag < 5s, 50 orders/sec sustained, service startup < 30s, HikariCP connection pools configured per service
- **Security** (NFR11-18): TLS for external and internal (gRPC) communication, JWT 15min / refresh 24h, K8s Secrets for sensitive config, Keycloak password management, API Gateway JWT validation, RBAC enforcement, audit trail
- **Scalability** (NFR19-25): K8s HPA per service, Kafka partition-based consumer scaling, 10x load tolerance with proportional scaling, database-per-service independence, Redis horizontal scaling, stateless services, K8s resource limits (256Mi/250m request, 512Mi/500m limit)
- **Reliability** (NFR26-37): Auto-recovery via Circuit Breaker + DLQ, Outbox Pattern data durability, DLQ 7-day retention, liveness restart < 30s, readiness probe traffic gating, Flyway zero-downtime migration, 30s eventual consistency window, >95% read availability during single-service failure, 60s full recovery, at-least-once Kafka delivery with idempotency, distributed lock TTL 10s with recovery, graceful shutdown within 30s termination period
- **Observability** (NFR38-42): End-to-end distributed tracing, correlation ID in logs/responses/Kafka headers, structured JSON logging with mandatory fields, health check < 1s response, Micrometer/Prometheus metrics
- **Data Consistency** (NFR43-45): Outbox replay verified by integration tests, Saga compensation < 10s, idempotency keys valid 24h
- **Code Quality** (NFR46-48): Shared project structure validated by ArchUnit, OpenAPI drift detection in CI, Protobuf backward-compatible evolution with buf lint
- **Development & Deployment** (NFR49-62): CI/CD < 15min, independent deployments, zero-downtime rolling updates, 80% unit test coverage, Testcontainers for all integrations, contract tests on every build, CI blocks on test failure, test suite < 10min per service, no flaky tests, backward-compatible schema changes (2 releases or 14 days), test reports with full context, isolated test data, chaos testing validates recovery

**NFR Tension Pairs (Require Architectural Resolution):**

| Tension | NFRs | Risk | Resolution Required |
|---------|------|------|-------------------|
| Latency vs TLS overhead | NFR1 (p95 < 200ms) vs NFR12 (gRPC TLS) | TLS handshake/encryption overhead on every inter-service gRPC call may erode latency budget | Connection pooling, TLS session resumption, or mTLS with long-lived connections |
| Concurrent orders vs Lock contention | NFR6 (100 concurrent orders) vs NFR36 (lock TTL 10s) | 100 simultaneous orders competing for distributed locks on same inventory item = severe contention | Lock granularity (per-SKU not global), lock acquisition timeout strategy, optimistic locking alternatives |
| Eventual consistency vs Real-time | NFR32 (30s consistency window) vs NFR5 (WebSocket < 1s) | Admin Dashboard receives events real-time but underlying data may be 30s stale — mental model conflict | Clear UX distinction between "live events" and "consistent state", or tighten consistency window for admin-facing queries |

**Scale & Complexity:**

- Primary domain: Full-stack distributed microservices (Java Spring Boot backend, Vue.js SPA frontend)
- Complexity level: High — 7 microservices, 5 communication protocols, 2 frontend applications, 13+ distributed system patterns, full infrastructure stack
- Estimated architectural components: ~20 (7 services + 2 frontends + API Gateway + Config Server + 6 infrastructure services + shared libraries)

### Technical Constraints & Dependencies

- **Language/Framework**: Java Spring Boot (backend), Vue.js 3 + PrimeVue + Tailwind CSS (frontend)
- **Infrastructure**: Kubernetes deployment mandatory, Docker containerization, GitHub Actions CI/CD
- **Data Stores**: PostgreSQL (per service), Redis (cart, caching, distributed locking), Elasticsearch (product search)
- **Messaging**: Kafka with KRaft (no Zookeeper) — event streaming backbone
- **Identity**: Keycloak (OAuth2/OIDC) — social login, RBAC, JWT management
- **Communication**: REST (external), gRPC (internal service-to-service), GraphQL (Product Service queries), Kafka (async events), WebSocket/STOMP (Admin real-time)
- **API Gateway**: Spring Cloud Gateway — routing, rate limiting, JWT validation, REST→gRPC translation
- **Configuration**: Spring Cloud Config Server or K8s ConfigMaps/Secrets
- **Database Migration**: Flyway for schema versioning
- **Observability**: Distributed tracing, Micrometer/Prometheus metrics, structured JSON logging
- **Testing**: JUnit, Testcontainers, Pact (contract), k6 (performance), Chaos testing
- **Test Infrastructure Constraint**: Integration tests require 10+ containers per service (PostgreSQL x6, Redis, Kafka, Elasticsearch, Keycloak). NFR56 target (test suite < 10min/service) at risk — container startup alone may consume 3-4 minutes. Shared Testcontainers configuration and container reuse strategy required.

### Architectural Risk Register

| Risk | Severity | Description |
|------|----------|-------------|
| **API Gateway overload** | HIGH | Gateway carries 6+ responsibilities: routing, rate limiting, JWT validation, REST-to-gRPC translation, response aggregation (FR66), API versioning. Single point of failure and potential performance bottleneck. |
| **Serialization complexity** | MEDIUM | 5 protocols use different serialization: JSON (REST/GraphQL), Protobuf (gRPC), Kafka events (Avro or JSON?). Correlation ID propagation across all 5 requires unified strategy. |
| **Shared library strategy undefined** | HIGH | 7 Java Spring Boot services share common concerns (exception handling, logging config, gRPC stubs, Kafka event schemas, test utilities). No strategy = code duplication; over-sharing = coupling. Architecture must decide early. |
| **Testing surface combinatorial** | MEDIUM | 7 services x 5 protocols = 35 communication paths needing contract testing. 3 different contract testing frameworks (Pact REST, Protobuf validation gRPC, schema registry Kafka) running in parallel per NFR62. |
| **Distributed lock contention** | HIGH | Flash sale scenario: 100 concurrent orders for same SKU through single Redis distributed lock with 10s TTL. Lock granularity and acquisition strategy critical for NFR6 compliance. |

### Cross-Cutting Concerns Identified

1. **Authentication & Authorization** — JWT propagation across all protocols (REST-to-gRPC-to-Kafka), API Gateway validation, Keycloak integration, RBAC enforcement
2. **Distributed Tracing & Correlation** — Trace ID and Correlation ID propagated through REST headers, gRPC metadata, Kafka message headers, and logged in every service — unified propagation strategy needed across 5 protocols
3. **Error Handling & Response Format** — Consistent `{code, message, traceId, timestamp}` across all services, gRPC status code mapping at API Gateway, `@ControllerAdvice` global handlers
4. **Event-Driven Communication** — Outbox Pattern for DB-to-Kafka guarantee, DLQ for failed messages, at-least-once delivery with idempotency, event schema backward compatibility, Kafka event serialization format decision (Avro vs JSON)
5. **Resilience Patterns** — Circuit Breaker (per-service), retry with exponential backoff, graceful degradation (tiered UX response), graceful shutdown, backpressure
6. **Caching Strategy** — Redis caching with configurable TTL (5min product details, 1min search results), Kafka event-driven cache invalidation
7. **Database Independence** — Database-per-service, Flyway migrations per service, no cross-service DB dependencies
8. **Structured Logging** — JSON format, mandatory fields (timestamp ISO-8601, level, service-name, trace-id, correlation-id, message), shared configuration
9. **API Versioning & Schema Evolution** — URL-based REST versioning (/api/v1/, /api/v2/), Protobuf backward-compatible evolution, Kafka event schema registry
10. **Health Monitoring** — Liveness/readiness probes (Spring Boot Actuator), custom health indicators per dependency, K8s pod management
11. **Shared Library Strategy** — Common library for cross-cutting code (exception handling, logging, security filters, gRPC stubs, Kafka event schemas, test utilities) vs service independence trade-off. Requires early architectural decision.
12. **Testing Surface Complexity** — 35 communication paths requiring contract testing across 3 frameworks (Pact REST, Protobuf gRPC, schema registry Kafka). Testcontainers resource optimization and shared test infrastructure strategy needed.

## Starter Template Evaluation

### Primary Technology Domain

**Hybrid Multi-Module Platform** — Java Spring Boot microservices backend (7 services) + Vue.js 3 SPA frontend (2 apps). Backend-dominant architecture with frontend as functional interface layer.

### Repository Strategy: Monorepo

**Decision:** Monorepo — all backend services, frontend apps, shared libraries, and infrastructure config in a single repository.

**Rationale:**
- Solo developer (4hrs/day) — single repo eliminates cross-repo coordination overhead
- Shared libraries (common-lib, security-lib, proto, events, test-support) are trivially consumed as Maven modules
- Atomic commits across services when changing shared contracts (gRPC .proto, Kafka event schemas)
- Single CI/CD pipeline — simpler GitHub Actions configuration
- Easier code review and refactoring across service boundaries

**Interview relevance:** "We chose monorepo because shared library changes and contract updates must be atomic. In a team setting, we'd evaluate polyrepo with artifact publishing when services reach independent deployment maturity and team boundaries form."

### Technology Versions (Verified March 2026)

| Technology | Version | Status | Fallback |
|-----------|---------|--------|----------|
| Java | 25 (LTS) | Recommended for Spring Boot 4 | Java 21 LTS if third-party compatibility issues arise |
| Spring Boot | 4.0.4 | Latest stable (March 19, 2026) | — |
| Spring Cloud | 2025.1 | Latest GA (Gateway 5.0.1, Config 5.0.1) | — |
| Spring Framework | 7.x | Required by Spring Boot 4 | — |
| Vue.js | 3.5.x / 3.6.x | Stable | — |
| create-vue | 3.18.5 | Latest (January 7, 2026, Vite 8) | — |
| PrimeVue | 4.3.9 | Latest stable | — |
| Vite | 8.x | Latest | — |
| Tailwind CSS | 3.x+ | Stable | — |

### Starter Options Considered

#### Option A: Spring Initializr per Service

Generate each microservice individually, assemble into multi-module Maven project manually.
- Pros: Official Spring tooling, per-service dependency selection
- Cons: No multi-module scaffolding, no shared library structure, repeated configuration

#### Option B: Custom Multi-Module Maven Template (Selected)

Hand-craft Maven multi-module project with parent POM defining shared configurations.
- Pros: Full control, shared modules defined upfront, Maven enterprise standard, interview-relevant
- Cons: XML verbosity, slower incremental builds (mitigated with parallel + selective builds)

#### Option C: create-vue for Frontend (Selected)

Use `create-vue` CLI for both SPAs with TypeScript, Vite, Router, Pinia.
- Pros: Official tooling, Vite 8 included
- Cons: PrimeVue and Tailwind added separately

### Selected Approach: Option B (Backend) + Option C (Frontend)

**Rationale for Backend — Custom Multi-Module Maven:**
- Maven is the enterprise standard — directly interview-relevant for Senior Java positions
- Parent POM + BOM pattern provides clean dependency management across 7 services
- `spring-boot-starter-parent` as parent POM gives production-ready defaults
- Shared libraries as Maven modules address the HIGH-severity shared library risk
- Maven parallel builds (`mvn -T 1C`) + selective builds (`mvn -pl :service -am`) keep CI/CD within NFR49 target
- Maven Wrapper (mvnw) ensures reproducible builds
- `maven-enforcer-plugin` enforces Java version consistency, dependency convergence, and banned dependencies across all modules

**Rationale for Frontend — create-vue:**
- Official scaffolding, minimal setup, Vite 8
- Two separate SPAs with npm workspaces for shared design token package
- PrimeVue 4.3.9 + Tailwind CSS added post-scaffold

### Initialization Commands

**Backend — Maven Multi-Module Project:**

```bash
mkdir robo-mart-backend && cd robo-mart-backend

# Parent pom.xml defines:
# - spring-boot-starter-parent as parent
# - dependencyManagement (Spring Cloud BOM, shared versions)
# - pluginManagement (spring-boot-maven-plugin, protobuf-maven-plugin, flyway, maven-enforcer-plugin)
# - maven-enforcer-plugin rules: requireJavaVersion, dependencyConvergence, banDuplicateClasses,
#   ban commons-logging (enforce SLF4J), ban junit-vintage-engine (enforce JUnit 5)
# - modules list
```

**Frontend — npm workspaces + create-vue:**

```bash
cd frontend
npm init -w shared -w customer-website -w admin-dashboard

npm create vue@latest customer-website -- \
  --typescript --router --pinia --vitest --eslint-with-prettier

npm create vue@latest admin-dashboard -- \
  --typescript --router --pinia --vitest --eslint-with-prettier

# shared/ package available as @robo-mart/shared
```

### Architectural Decisions Provided by Starter

**Language & Runtime:**
- Java 25 LTS with Spring Boot 4.0.4, Spring Framework 7.x (fallback: Java 21 LTS)
- TypeScript for both Vue.js frontends
- Maven with XML POM for build configuration

**Build Tooling:**
- Maven 3.9.x with Maven Wrapper (mvnw)
- Parent POM with `spring-boot-starter-parent` inheritance
- `dependencyManagement` with Spring Cloud BOM
- `maven-enforcer-plugin` for build consistency enforcement
- Vite 8 for frontend builds
- Docker multi-stage builds per service

**Shared Library Modules (Single Responsibility):**

| Module | Responsibility | Consumers |
|--------|---------------|-----------|
| `common-lib` | Shared exceptions, error response DTOs, logging config, base entity classes | All services |
| `security-lib` | JWT filter, security config, auth utilities | All services |
| `proto` | gRPC .proto files + generated stubs | Services with gRPC communication |
| `events` | Kafka event schemas (Avro/JSON) | Services publishing/consuming events |
| `test-support` | Shared Testcontainers configs, custom test annotations (`@IntegrationTest`, `@ContractTest`), base test classes, test data builders | All services (scope: test) |

**Project Structure:**

```
robo-mart/
├── backend/
│   ├── pom.xml                      # Parent POM
│   ├── .mvn/                        # Maven Wrapper
│   ├── common-lib/                  # Shared exceptions, logging, DTOs, base entities
│   │   └── pom.xml
│   ├── security-lib/                # JWT filter, security config, auth utilities
│   │   └── pom.xml
│   ├── proto/                       # gRPC .proto files + generated stubs
│   │   └── pom.xml
│   ├── events/                      # Kafka event schemas
│   │   └── pom.xml
│   ├── test-support/                # Shared test utilities (scope: test)
│   │   └── pom.xml
│   ├── api-gateway/
│   │   └── pom.xml
│   ├── product-service/
│   │   └── pom.xml
│   ├── cart-service/
│   │   └── pom.xml
│   ├── order-service/
│   │   └── pom.xml
│   ├── inventory-service/
│   │   └── pom.xml
│   ├── payment-service/
│   │   └── pom.xml
│   └── notification-service/
│       └── pom.xml
├── frontend/
│   ├── package.json                 # npm workspaces root
│   ├── shared/                      # @robo-mart/shared design tokens & components
│   │   ├── package.json
│   │   ├── tokens/
│   │   ├── themes/
│   │   └── components/
│   ├── customer-website/            # Vue.js SPA
│   │   └── package.json
│   └── admin-dashboard/             # Vue.js SPA
│       └── package.json
├── infra/
│   ├── docker/                      # Dockerfiles, docker-compose (dev)
│   │   └── pact-broker/             # Pact Broker for contract testing
│   ├── k8s/                         # Kubernetes manifests
│   └── ci/                          # GitHub Actions workflows
└── docs/
```

**Testing Framework:**
- Backend: JUnit 5 + Testcontainers + Pact + k6
- Frontend: Vitest + Vue Test Utils
- Contract Testing: Pact Broker in `infra/docker/pact-broker/` for local dev and CI/CD
- Shared test infrastructure via `test-support` module eliminates duplicate Testcontainers configs

**Code Organization:**
- Backend: controller/service/repository layers (ArchUnit validated per NFR46)
- Frontend: Vue 3 Composition API + SFC + feature-based directories

**Development Experience:**
- Maven parallel builds (`mvn -T 1C`) + selective module builds (`mvn -pl :order-service -am`)
- Vite HMR for instant frontend feedback
- docker-compose for local infrastructure (Kafka, Redis, PostgreSQL, Elasticsearch, Keycloak, Pact Broker)
- Spring Boot DevTools for backend hot-reload

**Note:** Project initialization using these commands should be the first implementation story.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
- Kafka event serialization format
- Saga orchestration approach
- gRPC framework selection
- Observability stack selection
- Shared library module boundaries

**Important Decisions (Shape Architecture):**
- GraphQL framework
- Circuit Breaker implementation
- mTLS strategy for inter-service gRPC
- Frontend HTTP/WebSocket client choices
- Log aggregation strategy

**Deferred Decisions (Can Evolve):**
- CQRS read model storage (PostgreSQL views initially, migrate to dedicated read store if needed)
- Image storage (local filesystem initially, migrate to S3/MinIO if needed)
- Rate limiting algorithm (token bucket default, configurable)

### Data Architecture

| Decision | Choice | Version | Rationale |
|----------|--------|---------|-----------|
| **ORM** | Spring Data JPA + Hibernate | Bundled with Spring Boot 4.0.4 | Spring Boot default, production-proven, database-per-service with JPA repositories per service |
| **Database Migration** | Flyway | Bundled with Spring Boot 4.0.4 | Each service manages own migrations independently. Seed data via Flyway repeatable migrations (`R__seed_data.sql`) activated by `demo` Spring profile |
| **Kafka Event Serialization** | Avro + Confluent Schema Registry | kafka-avro-serializer 7.x | Production standard. Schema evolution built-in (backward/forward compatible). NFR62 mandates "schema registry validation for Kafka events". Interview-relevant: explains schema evolution, compatibility modes, and consumer contract enforcement |
| **Redis Client** | Lettuce | Bundled with Spring Boot 4.0.4 | Spring Boot 4 default. Non-blocking, Netty-based, reactive support. Connection pooling built-in — important for distributed locking performance under NFR6 (100 concurrent orders) |
| **Elasticsearch Client** | Spring Data Elasticsearch | Bundled with Spring Boot 4.0.4 | Consistent with Spring Data ecosystem. Repository pattern alignment with JPA. Automatic index management |
| **Caching** | Spring Cache + Redis (Lettuce) | Bundled | Configurable TTL per data type (5min product details, 1min search results per FR64). Event-driven invalidation via Kafka per FR65 |

**Data Modeling Approach:**
- Each service owns its domain model via JPA entities
- No shared entity classes across services — only shared DTOs in `common-lib` for inter-service communication
- Outbox table pattern: dedicated `outbox_events` table per service for reliable Kafka publishing
- Idempotency keys stored in dedicated `idempotency_keys` table (Payment Service) with 24h TTL per NFR45

**Seed Data Strategy:**
- Flyway repeatable migrations (`R__seed_data.sql`) per service — only executed when `demo` Spring profile is active
- Product catalog: ~50 products across 5 categories with images, descriptions, prices, ratings
- Sample orders in various states (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED) for demo
- Keycloak realm export with pre-configured users: `demo-customer@robomart.com` (Customer role), `demo-admin@robomart.com` (Admin role)
- Seed data scripts version-controlled alongside schema migrations but clearly separated (`db/seed/` directory)

### Authentication & Security

| Decision | Choice | Version | Rationale |
|----------|--------|---------|-----------|
| **Identity Provider** | Keycloak | Latest stable | Already decided in PRD. OAuth2/OIDC, social login (Google, GitHub), RBAC |
| **JWT Validation** | API Gateway centralized | Spring Cloud Gateway filter | Single validation point — services trust gateway-forwarded requests. No service-level JWT validation duplication |
| **Inter-service Auth** | mTLS for gRPC | Spring gRPC TLS config | Resolves NFR1 vs NFR12 tension: mTLS with long-lived connections + TLS session resumption minimizes handshake overhead. Services authenticate each other via certificates, no JWT re-validation |
| **RBAC Enforcement** | API Gateway + Keycloak roles | Spring Security | Gateway extracts roles from JWT, enforces route-level access. Customer role vs Admin role per FR40 |
| **Secrets Management** | Kubernetes Secrets | K8s native | Per NFR14. DB passwords, API keys, TLS certificates stored in K8s Secrets, injected as environment variables |

**JWT Propagation Strategy (Unified Across 5 Protocols):**

| Protocol | Propagation Mechanism |
|----------|---------------------|
| REST (external → gateway) | `Authorization: Bearer <JWT>` header |
| gRPC (gateway → services) | gRPC metadata key `authorization` — gateway extracts user context, forwards as custom metadata (`x-user-id`, `x-user-roles`) |
| gRPC (service → service) | mTLS authenticates service identity. User context forwarded via gRPC metadata when needed |
| Kafka (async events) | User context embedded in Kafka message headers (`x-user-id`, `x-correlation-id`, `x-trace-id`) |
| WebSocket (admin dashboard) | JWT validated on STOMP CONNECT frame. Session authenticated for duration of WebSocket connection |

### API & Communication Patterns

| Decision | Choice | Version | Rationale |
|----------|--------|---------|-----------|
| **gRPC Framework** | Spring gRPC | 1.0.x (GA Dec 2025) | Official Spring project. Native Spring Boot 4 support. Will merge into Spring Boot 4.1. Replaces third-party starters |
| **GraphQL Framework** | Spring for GraphQL | Bundled with Spring Boot 4.0.4 | Official Spring project. DGS now builds on top of it internally. Use `@BatchMapping` for DataLoader pattern to solve N+1 problem |
| **Circuit Breaker** | Resilience4j | Spring Boot 4 starter available | Mature, widely adopted. Provides circuit breaker, retry, rate limiter, bulkhead, time limiter. Annotation-based (`@CircuitBreaker`, `@Retry`) |
| **Saga Orchestration** | Custom implementation (phased) | — | See detailed design below |
| **API Documentation** | SpringDoc OpenAPI | Latest stable | Auto-generates OpenAPI/Swagger from annotations. CI drift detection per NFR47 |
| **Protobuf Tooling** | protobuf-maven-plugin + buf lint | Latest stable | Proto compilation in `proto` module. buf lint validates backward compatibility per NFR48 |

**Saga Orchestration Design (Phased Approach):**

Custom implementation — build Saga orchestrator in Order Service. Interview answer: "We implemented Saga orchestration manually to understand state machine transitions, compensating transactions, and failure recovery."

**Phase A — Core Saga (with Order Flow in Phase 2):**
- Enum-based state machine: `PENDING → INVENTORY_RESERVING → PAYMENT_PROCESSING → CONFIRMED`
- Happy path + single compensation path (payment fails → release inventory)
- Order cancellation compensation: `CONFIRMED → PAYMENT_REFUNDING → INVENTORY_RELEASING → CANCELLED` (FR20-FR22)
- Saga state persisted in Order Service database (survives restart)
- `SagaStep` interface with `execute()` + `compensate()` methods

**Phase B — Hardened Saga (with Resilience in Phase 4):**
- Idempotent step execution (deduplication via saga step ID)
- Per-step timeout with configurable duration
- Dead saga detection (scheduled job finds stuck sagas > threshold, triggers compensation)
- Concurrent saga instance handling (100 simultaneous orders per NFR6)
- Saga audit log for debugging and interview demonstration

**Scope Acknowledgment:** Custom Saga orchestrator is effectively a mini workflow engine. This is the most complex single component in the system and should be allocated proportional development focus.

**Contract Testing Strategy (Per Protocol):**

| Protocol | Contract Mechanism | Rationale |
|----------|-------------------|-----------|
| REST APIs | Pact (consumer-driven) | Standard for REST contract testing. Pact Broker for contract sharing |
| gRPC | Protobuf schema validation + buf lint | Proto files ARE the contract. buf lint enforces backward compatibility. No need for Pact layer |
| Kafka Events | Confluent Schema Registry (Avro compatibility check) + integration tests | Schema Registry handles schema contract (backward/forward compat). Semantic contract validated by integration tests with Testcontainers. Pact not natively suited for Avro |

**Trace Context Propagation (Explicit Configuration):**

| Protocol | Propagation Config Required |
|----------|---------------------------|
| REST | Auto-instrumented by OpenTelemetry Spring Boot starter — no manual config |
| gRPC | Spring gRPC 1.0 supports trace context via gRPC interceptors — requires explicit `GrpcTracingInterceptor` bean registration in `common-lib` |
| Kafka | Requires `TracingProducerInterceptor` and `TracingConsumerInterceptor` configuration in Kafka producer/consumer factories — configured in `common-lib` |
| JDBC | Auto-instrumented by OpenTelemetry — no manual config |
| WebSocket | Manual trace ID injection on STOMP message headers — configured in WebSocket handler |

**Error Handling Standards:**

```json
{
  "code": "PAYMENT_DECLINED",
  "message": "Payment could not be processed",
  "traceId": "abc123-def456",
  "timestamp": "2026-03-27T10:30:00Z"
}
```

- All services use `@ControllerAdvice` global exception handler (from `common-lib`)
- gRPC services return `Status` codes mapped to equivalent REST codes at API Gateway
- Kafka DLQ messages include original error + trace ID for debugging

### Frontend Architecture

| Decision | Choice | Version | Rationale |
|----------|--------|---------|-----------|
| **State Management** | Pinia | Bundled with create-vue | Official Vue.js state management. Composition API native. TypeScript support |
| **HTTP Client** | Axios | Latest stable | Standard for Vue.js. Interceptors for JWT attachment, error handling, retry logic |
| **WebSocket Client** | SockJS + STOMP.js | Latest stable | Matches Spring WebSocket + STOMP backend. SockJS provides fallback. STOMP provides topic-based pub/sub |
| **Routing** | Vue Router | Bundled with create-vue | Route guards for auth. Lazy loading for code splitting |
| **Form Validation** | VeeValidate + Yup | Latest stable | Schema-based validation. Integrates with PrimeVue form components. Inline validation on blur per UX spec |
| **Component Library** | PrimeVue | 4.3.9 | Already decided in UX spec. 90+ components. Aura theme preset |
| **CSS Framework** | Tailwind CSS | 3.x+ | Already decided in UX spec. Utility-first. Shared tokens via @robo-mart/shared |

**Frontend State Architecture:**

```
Pinia Stores (per feature domain):
├── authStore        — JWT tokens, user profile, login/logout
├── cartStore        — Cart items, totals, optimistic updates
├── productStore     — Product list, search results, filters, cache
├── orderStore       — Order history, order detail, status tracking
├── adminStore       — Dashboard metrics, alerts, product CRUD
├── websocketStore   — WebSocket connection state, live events buffer
└── uiStore          — Toast notifications, loading states, degradation tier
```

### Infrastructure & Deployment

| Decision | Choice | Version | Rationale |
|----------|--------|---------|-----------|
| **Observability** | OpenTelemetry | spring-boot-starter-opentelemetry (Spring Boot 4 native) | First-class Spring Boot 4 support. Vendor-neutral. Auto-instrumentation for HTTP, JDBC. Manual config required for gRPC and Kafka trace propagation (see Trace Context section) |
| **Tracing Backend** | Grafana Tempo | Latest stable | Cost-effective (object storage). Integrates natively with Grafana stack. Simpler than Jaeger. OpenTelemetry compatible |
| **Metrics** | Micrometer → Prometheus | Prometheus 3.9.x | Spring Boot Actuator + Micrometer auto-configured. Prometheus scrapes /actuator/prometheus endpoint. Per NFR42 |
| **Log Aggregation** | Grafana Loki + Alloy | Loki 3.x, Alloy (replaces Promtail) | Lightweight, label-based log aggregation. Alloy ships logs from K8s pods |
| **Dashboards** | Grafana | 12.3.x | Unified dashboards for metrics (Prometheus), logs (Loki), traces (Tempo). Single pane of glass |
| **Container Registry** | GitHub Container Registry (ghcr.io) | — | Integrated with GitHub Actions CI/CD |
| **K8s Package Manager** | Helm | Latest stable | Templated K8s manifests per service. Environment-specific values files |
| **Local Dev Infrastructure** | Docker Compose with profiles | Latest stable | See profiles below |

**Docker Compose Profiles:**

| Profile | Containers | RAM Estimate | Use Case |
|---------|-----------|-------------|----------|
| `core` | PostgreSQL x6, Redis, Kafka, Elasticsearch, Keycloak, Schema Registry | ~6-8 GB | Daily development — sufficient for most coding tasks |
| `full` | Core + Pact Broker + Grafana + Prometheus + Loki + Tempo + Alloy | ~12-16 GB | Full observability testing, demo preparation, integration testing |

Total container count: 12 (core) / 18 (full). Minimum system requirement: 16GB RAM recommended, 32GB for comfortable `full` profile usage.

**Observability Stack Architecture:**

```
┌─────────────────────────────────────────────────────────┐
│                    Grafana (12.3.x)                      │
│         Dashboards: Metrics + Logs + Traces              │
└───────┬──────────────────┬──────────────────┬────────────┘
        │                  │                  │
   Prometheus          Grafana Loki      Grafana Tempo
   (metrics)            (logs)            (traces)
        ▲                  ▲                  ▲
        │                  │                  │
   /actuator/          Alloy              OTLP
   prometheus         (log shipper)      exporter
        ▲                  ▲                  ▲
        │                  │                  │
   ┌────┴──────────────────┴──────────────────┴────┐
   │          Spring Boot Services (x7)             │
   │   Micrometer + OpenTelemetry auto-instrument   │
   │   Structured JSON logs + Trace/Correlation IDs │
   └────────────────────────────────────────────────┘
```

### Decision Impact Analysis

**Implementation Sequence (Vertical Slice Approach):**

1. Infrastructure: Docker Compose profiles, Maven parent POM, shared library modules
2. Product Service + Customer Website search/browse — **first working vertical slice** (JPA, Flyway, Elasticsearch, Outbox, Avro, Vue.js search UI)
3. Cart Service + Customer Website cart — (Redis, Lettuce, cart UI)
4. API Gateway + Keycloak integration + mTLS setup + Customer Website auth (login, social login, cart merge)
5. Order Service + Inventory Service + Payment Service — Saga Phase A (core flow + compensation)
6. Customer Website checkout + order tracking
7. Admin Dashboard — product CRUD, inventory, orders, reporting
8. Notification Service (Kafka consumer, DLQ)
9. Admin Dashboard real-time (WebSocket, system health, DLQ management)
10. Observability stack integration (OpenTelemetry, Tempo, Loki) + Saga Phase B (hardened)
11. Testing pyramid (test-support module, Pact Broker, k6, chaos tests)

**Cross-Component Dependencies:**

| Decision | Affects |
|----------|---------|
| Avro + Schema Registry | `events` module, all Kafka producers/consumers, CI/CD (schema compatibility check) |
| Spring gRPC | `proto` module, API Gateway (REST→gRPC), all inter-service sync calls |
| Resilience4j | All services making sync calls (gRPC client side), API Gateway |
| Custom Saga (Phased) | Order Service (orchestrator), Inventory Service, Payment Service. Phase A in step 5, Phase B in step 10 |
| OpenTelemetry | All services, `common-lib` (gRPC/Kafka trace interceptors). Integrated in step 10 |
| mTLS | All gRPC connections, K8s certificate management, `security-lib` |
| Pinia stores | Both frontend apps, WebSocket integration |
| Seed data | Flyway per service, Keycloak realm export, `demo` profile |

**Test Support Module Contents:**

| Component | Purpose |
|-----------|---------|
| `@IntegrationTest` | Custom annotation combining Spring test + Testcontainers lifecycle |
| `@ContractTest` | Custom annotation for Pact provider/consumer test configuration |
| `PostgresContainerConfig` | Shared PostgreSQL Testcontainers setup (reusable across 6 services) |
| `KafkaContainerConfig` | Shared Kafka + Schema Registry Testcontainers setup |
| `RedisContainerConfig` | Shared Redis Testcontainers setup |
| `KeycloakContainerConfig` | Shared Keycloak Testcontainers setup with test realm |
| `SagaTestHelper` | Utility to setup saga test scenarios: create order in specific state, simulate step failure, verify compensation |
| `TestDataBuilder` | Builder pattern for common test entities (Product, Order, CartItem, etc.) |
| `EventAssertions` | Custom assertions for verifying Kafka events (Avro deserialization + content matching) |

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

**Critical Conflict Points Identified:** 38 areas where AI agents could make different choices, organized into 5 categories: Naming (14), Structure (8), Format (7), Communication (5), Process (4).

### Naming Patterns

**Database Naming Conventions:**

| Element | Convention | Example |
|---------|-----------|---------|
| Tables | `snake_case`, plural | `products`, `order_items`, `outbox_events` |
| Columns | `snake_case` | `product_id`, `created_at`, `unit_price` |
| Primary keys | Always `id` (`BIGINT AUTO_INCREMENT`) | `id` |
| Foreign keys | `{referenced_table_singular}_id` | `product_id`, `order_id` |
| Indexes | `idx_{table}_{columns}` | `idx_products_category_id` |
| Unique constraints | `uk_{table}_{columns}` | `uk_users_email` |
| Outbox table | `outbox_events` (consistent across all services) | — |
| Idempotency table | `idempotency_keys` (Payment Service only) | — |

**API Naming Conventions:**

| Element | Convention | Example |
|---------|-----------|---------|
| REST endpoints | Plural nouns, `kebab-case` | `/api/v1/products`, `/api/v1/order-items` |
| Path parameters | `{camelCase}` | `/api/v1/products/{productId}` |
| Query parameters | `camelCase` | `?categoryId=5&sortBy=price&pageSize=20` |
| Custom headers | `X-` prefix | `X-Correlation-Id`, `X-User-Id`, `X-User-Roles` |
| API versioning | URL-based | `/api/v1/`, `/api/v2/` |
| Pagination | 0-based page index | `?page=0&size=20` (default size=20, max size=100) |

**gRPC Naming Conventions:**

| Element | Convention | Example |
|---------|-----------|---------|
| Package | `com.robomart.{service}.grpc` | `com.robomart.product.grpc` |
| Service | `{Domain}Service` | `ProductService`, `InventoryService` |
| RPC methods | `PascalCase` verb-noun | `GetProduct`, `ReserveInventory` |
| Messages | `PascalCase` | `ProductResponse`, `ReserveInventoryRequest` |

**Kafka Event Naming Conventions:**

| Element | Convention | Example |
|---------|-----------|---------|
| Topic | `{service}.{entity}.{event}` | `product.product.created`, `order.order.status-changed` |
| Avro namespace | `com.robomart.events.{domain}` | `com.robomart.events.order` |
| Avro record | `{Entity}{Event}Event` | `OrderStatusChangedEvent`, `ProductCreatedEvent` |
| Consumer group | `{consuming-service}-{topic}-group` | `order-service-inventory.stock.reserved-group` |

**Java Code Naming Conventions:**

| Element | Convention | Example |
|---------|-----------|---------|
| Packages | `com.robomart.{service}.{layer}` | `com.robomart.product.controller` |
| Classes | `PascalCase` | `ProductController`, `OrderSagaOrchestrator` |
| Methods/variables | `camelCase` | `findByCategory()`, `totalAmount` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_RETRY_ATTEMPTS`, `SAGA_TIMEOUT_SECONDS` |
| DTOs | `{Entity}{Action}Request/Response` | `CreateProductRequest`, `OrderDetailResponse` |
| Exceptions | `{Domain}{Problem}Exception` | `ProductNotFoundException`, `InsufficientStockException` |
| Config prefix | `robomart.{service}` | `robomart.cart.ttl-minutes=30` |
| Mapper classes | MapStruct `@Mapper` | `ProductMapper`, `OrderMapper` |
| Import ordering | `java.*` → `javax.*` → `org.*` → `com.*` → `com.robomart.*` → static | Enforced via `.editorconfig` + Checkstyle |

**Vue.js/TypeScript Code Naming Conventions:**

| Element | Convention | Example |
|---------|-----------|---------|
| Components | `PascalCase.vue` | `ProductCard.vue`, `OrderStatusBadge.vue` |
| Composables | `use{Feature}.ts` | `useCart.ts`, `useProductSearch.ts` |
| Pinia stores | `use{Domain}Store.ts` | `useCartStore.ts`, `useAuthStore.ts` |
| Interfaces | `PascalCase` (no `I` prefix) | `Product`, `OrderDetail`, `CartItem` |
| API services | `{domain}Api.ts` | `productApi.ts`, `orderApi.ts` |
| Constants | `UPPER_SNAKE_CASE` in `constants.ts` | `MAX_CART_ITEMS`, `API_BASE_URL` |
| Event handlers | `on{Event}` | `onAddToCart`, `onSubmitOrder` |
| Booleans | `is{State}` / `has{Feature}` | `isLoading`, `hasError`, `isAuthenticated` |

### Structure Patterns

**Backend Service Structure (per service):**

```
{service-name}/
├── src/main/java/com/robomart/{service}/
│   ├── config/              # @Configuration classes
│   ├── controller/          # @RestController, @GrpcService
│   ├── dto/                 # Service-specific Request/Response DTOs
│   ├── entity/              # JPA @Entity classes
│   ├── exception/           # Service-specific exceptions
│   ├── mapper/              # MapStruct @Mapper interfaces (entity ↔ DTO)
│   ├── repository/          # Spring Data JPA repositories
│   ├── service/             # Business logic (@Service)
│   ├── saga/                # (Order Service only) Saga orchestrator
│   ├── event/               # Kafka producers/consumers
│   │   ├── producer/
│   │   └── consumer/
│   └── {ServiceName}Application.java
├── src/main/resources/
│   ├── application.yml       # Main config
│   ├── application-dev.yml   # Dev profile overrides
│   ├── application-demo.yml  # Demo profile (seed data activation)
│   ├── application-test.yml  # Test profile (Testcontainers overrides)
│   ├── db/
│   │   ├── migration/        # Flyway versioned: V1__init.sql, V2__add_index.sql
│   │   └── seed/             # Flyway repeatable: R__seed_data.sql
│   └── proto/                # (if gRPC provider) .proto references
└── src/test/java/com/robomart/{service}/
    ├── unit/                 # Pure unit tests (mocked dependencies)
    ├── integration/          # @IntegrationTest (Testcontainers)
    └── contract/             # @ContractTest (Pact provider verification)
```

**DTO Location Rule:**
- Service-specific DTOs (used only within that service) → `{service}/dto/`
- Shared DTOs (used by 2+ services: error response, pagination wrapper, common enums) → `common-lib/dto/`
- Rule: if a DTO is consumed by only 1 service, it stays in that service's `dto/` package. Move to `common-lib` only when a second consumer appears.

**Frontend Structure (per app):**

```
{app-name}/
├── src/
│   ├── api/                  # API service modules (Axios instances)
│   ├── assets/               # Static images, icons
│   ├── components/
│   │   ├── common/           # Reusable across features (AppHeader, AppFooter)
│   │   └── {feature}/        # Feature-specific (ProductCard, CartDrawer)
│   ├── composables/          # Shared composition functions
│   ├── layouts/              # Page layouts (DefaultLayout, AdminLayout)
│   ├── pages/                # Route-mapped page components
│   │   └── {feature}/        # Feature page groups
│   ├── router/               # Vue Router config
│   │   └── index.ts
│   ├── stores/               # Pinia stores
│   ├── types/                # TypeScript type definitions
│   ├── utils/                # Pure utility functions
│   └── App.vue
├── public/
└── index.html
```

**Test Location Rules:**
- Backend: tests in `src/test/java/` mirroring `src/main/java/` structure, separated by `unit/`, `integration/`, `contract/`
- Frontend: co-located `*.spec.ts` files next to the component/module they test → `ProductCard.spec.ts` beside `ProductCard.vue`

**`application.yml` Key Ordering Convention:**

```yaml
server:                # Server config (port, context-path)
spring:                # Spring framework
  datasource:          #   Database
  jpa:                 #   JPA/Hibernate
  kafka:               #   Kafka producer/consumer
  redis:               #   Redis connection
  cache:               #   Cache config
robomart:              # Custom application config
management:            # Actuator endpoints
resilience4j:          # Circuit breaker config
```

### Format Patterns

**REST API Response Wrapper:**

Success (single):
```json
{
  "data": { "id": 1, "name": "Robot Toy", "price": 29.99 },
  "traceId": "abc123"
}
```

Success (list with pagination):
```json
{
  "data": [ ... ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 156,
    "totalPages": 8
  },
  "traceId": "abc123"
}
```

Error:
```json
{
  "error": {
    "code": "PRODUCT_NOT_FOUND",
    "message": "Product with id 42 not found",
    "details": null
  },
  "traceId": "abc123",
  "timestamp": "2026-03-27T10:30:00Z"
}
```

**GraphQL Response Format:**
GraphQL endpoints follow the **native GraphQL specification** — no custom wrapper. Responses use standard `data` + `errors` structure per GraphQL spec. Do NOT apply the REST API response wrapper to GraphQL endpoints.

**HTTP Status Code Usage:**

| Scenario | Status Code |
|----------|-------------|
| Success (GET, PUT) | 200 OK |
| Created (POST) | 201 Created |
| Accepted (async operation) | 202 Accepted |
| No Content (DELETE) | 204 No Content |
| Validation error | 400 Bad Request |
| Unauthenticated | 401 Unauthorized |
| Forbidden (wrong role) | 403 Forbidden |
| Not found | 404 Not Found |
| Conflict (duplicate, race) | 409 Conflict |
| Rate limited | 429 Too Many Requests |
| Server error | 500 Internal Server Error |
| Service unavailable (circuit open) | 503 Service Unavailable |

**gRPC ↔ REST Status Code Mapping (at API Gateway):**

| gRPC Status | REST Status |
|-------------|-------------|
| OK | 200 |
| NOT_FOUND | 404 |
| INVALID_ARGUMENT | 400 |
| ALREADY_EXISTS | 409 |
| PERMISSION_DENIED | 403 |
| UNAUTHENTICATED | 401 |
| UNAVAILABLE | 503 |
| INTERNAL | 500 |

**JSON Conventions:**

| Rule | Convention |
|------|-----------|
| Field naming | `camelCase` → `productId`, `unitPrice`, `createdAt` |
| Jackson config | `PropertyNamingStrategies.LowerCamelCaseStrategy` configured globally in `common-lib` |
| Dates | ISO-8601 UTC string → `"2026-03-27T10:30:00Z"` |
| Money | `BigDecimal` serialized as number with 2 decimal places |
| Null fields | Excluded from response (`@JsonInclude(Include.NON_NULL)`) |

**Error Codes Convention:**
- Format: `{DOMAIN}_{PROBLEM}` in `UPPER_SNAKE_CASE`
- Centralized in `common-lib` as `ErrorCode` enum class
- Each service can extend with service-specific codes but must not duplicate existing codes
- Examples: `PRODUCT_NOT_FOUND`, `CART_EXPIRED`, `ORDER_ALREADY_CANCELLED`, `PAYMENT_DECLINED`, `INVENTORY_INSUFFICIENT`, `AUTH_TOKEN_EXPIRED`

### Communication Patterns

**Kafka Event Envelope (Avro):**

Every event record includes standard fields:
```
{
  "eventId": "uuid",           // Unique event ID for idempotency
  "eventType": "ORDER_CREATED", // Enum string
  "aggregateId": "12345",      // Entity ID that triggered event
  "aggregateType": "ORDER",    // Entity type
  "timestamp": 1711529400000,  // Unix millis
  "version": 1,                // Schema version
  "payload": { ... }           // Domain-specific data
}
```

Kafka headers (not in Avro, propagated separately):
- `x-correlation-id`: Request correlation ID
- `x-trace-id`: OpenTelemetry trace ID
- `x-user-id`: Originating user ID (when applicable)

**Outbox Pattern Implementation:**

```sql
CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    published_at   TIMESTAMP
);
```

- Phase 1: Polling-based publisher (polling interval: **1 second**, batch size: **50 events**)
- Published events marked `published = true` with timestamp
- Cleanup job: remove published events older than **7 days** (scheduled daily)
- Phase 2 (optional): Migrate to Debezium CDC if polling latency becomes an issue

**Pinia Store Pattern (Frontend):**

```typescript
// stores/useCartStore.ts
export const useCartStore = defineStore('cart', () => {
  // State
  const items = ref<CartItem[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  // Getters
  const totalItems = computed(() => items.value.reduce((sum, i) => sum + i.quantity, 0))
  const totalPrice = computed(() => items.value.reduce((sum, i) => sum + i.unitPrice * i.quantity, 0))

  // Actions
  async function addItem(productId: string, quantity: number) { ... }
  async function removeItem(itemId: string) { ... }

  return { items, isLoading, error, totalItems, totalPrice, addItem, removeItem }
})
```

- Always use Composition API style (`setup()` function)
- State: `ref()` for primitives/arrays, `reactive()` only for complex nested objects
- Loading/error state per store: `isLoading`, `error`
- Optimistic updates for cart operations, rollback on error

### Process Patterns

**Backend Exception Hierarchy:**

```
RoboMartException (abstract base)
├── ResourceNotFoundException (→ 404)
├── BusinessRuleException (→ 409)
├── ValidationException (→ 400)
└── ExternalServiceException (→ 503)
```

- Service layer throws domain exceptions → `ProductNotFoundException extends ResourceNotFoundException`
- `@ControllerAdvice` in `common-lib` catches and maps to error response wrapper
- Unhandled exceptions → 500 with generic message (no stack trace in response)
- Logging: `WARN` for 4xx, `ERROR` for 5xx — all include trace ID

**Frontend Error Handling:**
- Axios interceptor in `api/client.ts`: 401 → redirect to login, 503 → degradation banner
- Per-request error: caught in store action, set `error` ref, displayed via PrimeVue `Toast`
- Global error: `app.config.errorHandler` → log to console + generic toast
- Network error: "Connection lost" banner with auto-retry indicator

**Structured Logging Format (Backend):**

```json
{
  "timestamp": "2026-03-27T10:30:00.123Z",
  "level": "INFO",
  "service": "order-service",
  "traceId": "abc123",
  "correlationId": "req-456",
  "logger": "c.r.order.service.OrderService",
  "message": "Order created successfully",
  "context": { "orderId": 123, "userId": 42, "totalAmount": 59.98 }
}
```

- Log levels: `ERROR` (system failures), `WARN` (business rule violations, 4xx), `INFO` (business events), `DEBUG` (development details)
- NEVER log: passwords, JWT tokens, full credit card numbers, PII beyond userId
- Context data: use structured `context` field, not string interpolation

**Loading State Pattern (Frontend):**
- Each async operation: `isLoading` flag in store
- Skeleton loaders for initial page load (PrimeVue `Skeleton` component)
- Spinner overlay for user-initiated actions (add to cart, place order)
- Button loading state: disable + show spinner on the clicked button itself
- No full-page loading screens — prefer skeleton/progressive loading

**Validation Pattern:**
- Backend: Bean Validation (`@NotNull`, `@Size`, `@Valid`) on DTOs at controller level → reject early
- Frontend: VeeValidate + Yup schema → inline validation on blur, form-level on submit
- Error messages: backend returns `code` (machine-readable), frontend maps to localized message

### Testing Conventions

**Test Naming Convention:**
- Format: `should{Expected}When{Condition}()`
- Examples: `shouldReturnProductWhenValidId()`, `shouldThrowNotFoundExceptionWhenInvalidId()`, `shouldReserveInventoryWhenStockAvailable()`
- All test methods follow this pattern — no exceptions

**Assertion Library:**
- **AssertJ** for all assertions across all test types (unit, integration, contract)
- `assertThat(product.getName()).isEqualTo("Robot Toy")` — NOT `assertEquals("Robot Toy", product.getName())`
- Custom assertions in `test-support` module (e.g., `EventAssertions`) also extend AssertJ

**Test Data Builders (Mandatory):**

```java
// All test data created via TestData builders — NEVER use new Entity() + setters
Product product = TestData.product()
    .withName("Robot Toy")
    .withPrice(BigDecimal.valueOf(29.99))
    .withCategory("Toys")
    .build();

Order order = TestData.order()
    .withStatus(OrderStatus.CONFIRMED)
    .withItems(List.of(TestData.orderItem().build()))
    .build();
```

**Testcontainers Strategy:**
- Singleton containers: static container instances shared across all tests in a service
- Configuration: `application-test.yml` per service overrides datasource URL to Testcontainers
- Container reuse: `testcontainers.reuse.enable=true` in `.testcontainers.properties`
- Shared configs: `PostgresContainerConfig`, `KafkaContainerConfig`, `RedisContainerConfig`, `KeycloakContainerConfig` in `test-support` module

### Enforcement Guidelines

**All AI Agents MUST:**

1. Follow naming conventions exactly — no ad-hoc variations
2. Use the REST API response wrapper format for ALL REST endpoints (GraphQL follows native spec)
3. Place files in the prescribed directory structure
4. Include `traceId` in every API response and log entry
5. Use the Kafka event envelope format for ALL events
6. Handle errors through the exception hierarchy — never return raw exceptions
7. Use `camelCase` in JSON, `snake_case` in database, `PascalCase` for Java classes and Vue components
8. Write tests in the correct subdirectory (`unit/`, `integration/`, `contract/`)
9. Use MapStruct for all entity ↔ DTO mappings
10. Use AssertJ for all test assertions
11. Use TestData builders for all test data — never `new Entity()` + setters
12. Name test methods `should{Expected}When{Condition}()`
13. Use `ErrorCode` enum from `common-lib` for error codes
14. Follow `application.yml` key ordering convention

**Pattern Enforcement:**
- ArchUnit rules validate backend layer structure (controller → service → repository, no bypassing)
- ESLint + Prettier enforce frontend code style
- `.editorconfig` + Checkstyle enforce Java import ordering and code format
- `maven-enforcer-plugin` validates dependency and Java version consistency
- buf lint validates Protobuf backward compatibility
- PR review checklist includes pattern compliance check

### Pattern Examples

**Good Examples:**

```java
// ✅ Correct: MapStruct mapper, proper naming, error code enum
@Mapper(componentModel = "spring")
public interface ProductMapper {
    ProductDetailResponse toDetailResponse(Product entity);
    Product toEntity(CreateProductRequest request);
}

// ✅ Correct: Exception from hierarchy, error code from enum
throw new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product with id " + id + " not found");

// ✅ Correct: Test naming + AssertJ + TestData builder
@Test
void shouldReturnProductWhenValidId() {
    Product product = TestData.product().withName("Robot Toy").build();
    when(productRepository.findById(1L)).thenReturn(Optional.of(product));

    ProductDetailResponse result = productService.getProduct(1L);

    assertThat(result.getName()).isEqualTo("Robot Toy");
}
```

**Anti-Patterns:**

```java
// ❌ Wrong: Manual mapping instead of MapStruct
ProductResponse response = new ProductResponse();
response.setName(product.getName());
response.setPrice(product.getPrice());

// ❌ Wrong: String error code instead of enum
throw new RuntimeException("product_not_found");

// ❌ Wrong: JUnit assertions, no TestData builder, bad test name
@Test
void testGetProduct() {
    Product product = new Product();
    product.setName("Robot Toy");
    assertEquals("Robot Toy", productService.getProduct(1L).getName());
}

// ❌ Wrong: Custom REST wrapper on GraphQL endpoint
@QueryMapping
public ApiResponse<Product> product(@Argument Long id) { ... }
```

## Project Structure & Boundaries

### Configuration Decision: No Config Server

**Decision:** Spring Cloud Config Server is NOT used. Each service manages configuration via `application.yml` per profile + K8s ConfigMaps/Secrets for production.

**Rationale:** Config Server adds 1 container + 1 Git config repo — over-engineering for a learning project. Per-service `application.yml` with Spring profiles (`dev`, `demo`, `test`) is sufficient. K8s ConfigMaps handle production environment-specific config.

### Complete Project Directory Structure

```
robo-mart/
├── .github/
│   └── workflows/
│       ├── ci-backend.yml              # Backend CI: build, test, lint, contract
│       ├── ci-frontend.yml             # Frontend CI: build, test, lint
│       ├── cd-deploy.yml               # CD: Docker build, push ghcr.io, K8s deploy
│       └── schema-compatibility.yml    # Avro schema + Protobuf backward compat check
├── .editorconfig                       # Java import ordering, indentation, charset
├── .gitignore
├── README.md
│
├── backend/
│   ├── pom.xml                         # Parent POM (spring-boot-starter-parent)
│   ├── .mvn/
│   │   └── wrapper/
│   │       ├── maven-wrapper.jar
│   │       └── maven-wrapper.properties
│   ├── mvnw
│   ├── mvnw.cmd
│   ├── .testcontainers.properties      # testcontainers.reuse.enable=true
│   ├── config/
│   │   └── checkstyle/
│   │       └── checkstyle.xml          # Import ordering, code format rules
│   │
│   ├── common-lib/
│   │   ├── pom.xml
│   │   └── src/main/
│   │       ├── java/com/robomart/common/
│   │       │   ├── dto/
│   │       │   │   ├── ApiResponse.java            # REST response wrapper {data, traceId}
│   │       │   │   ├── ApiErrorResponse.java       # Error wrapper {error, traceId, timestamp}
│   │       │   │   ├── PagedResponse.java          # Paginated wrapper {data, pagination, traceId}
│   │       │   │   └── PaginationMeta.java         # {page, size, totalElements, totalPages}
│   │       │   ├── enums/
│   │       │   │   └── ErrorCode.java              # Centralized error codes enum
│   │       │   ├── exception/
│   │       │   │   ├── RoboMartException.java      # Abstract base
│   │       │   │   ├── ResourceNotFoundException.java  # → 404
│   │       │   │   ├── BusinessRuleException.java      # → 409
│   │       │   │   ├── ValidationException.java        # → 400
│   │       │   │   └── ExternalServiceException.java   # → 503
│   │       │   ├── handler/
│   │       │   │   └── GlobalExceptionHandler.java # @ControllerAdvice
│   │       │   ├── config/
│   │       │   │   ├── JacksonConfig.java          # camelCase, NON_NULL, ISO dates
│   │       │   │   ├── LoggingConfig.java          # Structured JSON logging config
│   │       │   │   └── TracingConfig.java          # gRPC + Kafka trace interceptors
│   │       │   ├── entity/
│   │       │   │   └── BaseEntity.java             # id, createdAt, updatedAt
│   │       │   └── util/
│   │       │       └── CorrelationIdFilter.java    # Servlet filter for X-Correlation-Id
│   │       └── resources/
│   │           └── logback-spring.xml              # Shared structured JSON logging config
│   │
│   ├── security-lib/
│   │   ├── pom.xml
│   │   └── src/main/java/com/robomart/security/
│   │       ├── config/
│   │       │   └── SecurityConfig.java         # Base security config (Keycloak)
│   │       ├── filter/
│   │       │   └── JwtAuthenticationFilter.java
│   │       └── util/
│   │           ├── AuthContext.java             # Extract user from SecurityContext
│   │           └── RoleConstants.java           # ROLE_CUSTOMER, ROLE_ADMIN
│   │
│   ├── proto/
│   │   ├── pom.xml                             # protobuf-maven-plugin + buf lint
│   │   └── src/main/proto/
│   │       ├── product_service.proto
│   │       ├── inventory_service.proto
│   │       ├── payment_service.proto
│   │       ├── order_service.proto
│   │       └── common/
│   │           └── types.proto                 # Shared proto types (Money, Address)
│   │
│   ├── events/
│   │   ├── pom.xml                             # kafka-avro-serializer dependency
│   │   └── src/main/avro/
│   │       ├── common/
│   │       │   └── event_envelope.avsc         # Base event envelope schema
│   │       ├── product/
│   │       │   ├── product_created.avsc
│   │       │   ├── product_updated.avsc
│   │       │   └── product_deleted.avsc
│   │       ├── order/
│   │       │   ├── order_created.avsc
│   │       │   ├── order_status_changed.avsc
│   │       │   └── order_cancelled.avsc
│   │       ├── inventory/
│   │       │   ├── stock_reserved.avsc
│   │       │   ├── stock_released.avsc
│   │       │   └── stock_low_alert.avsc
│   │       ├── payment/
│   │       │   ├── payment_processed.avsc
│   │       │   └── payment_refunded.avsc
│   │       └── notification/
│   │           └── notification_requested.avsc
│   │
│   ├── test-support/
│   │   ├── pom.xml
│   │   └── src/main/java/com/robomart/test/
│   │       ├── annotation/
│   │       │   ├── IntegrationTest.java        # @IntegrationTest composite annotation
│   │       │   └── ContractTest.java           # @ContractTest composite annotation
│   │       ├── container/
│   │       │   ├── PostgresContainerConfig.java
│   │       │   ├── KafkaContainerConfig.java   # Kafka + Schema Registry
│   │       │   ├── RedisContainerConfig.java
│   │       │   ├── ElasticsearchContainerConfig.java
│   │       │   └── KeycloakContainerConfig.java
│   │       ├── data/
│   │       │   └── TestData.java               # Builder entry: TestData.product(), TestData.order()
│   │       ├── saga/
│   │       │   └── SagaTestHelper.java         # Saga scenario setup utilities
│   │       └── assertion/
│   │           └── EventAssertions.java        # Avro event assertions (AssertJ-based)
│   │
│   ├── api-gateway/
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/robomart/gateway/
│   │       │   ├── config/
│   │       │   │   ├── RouteConfig.java            # Route definitions (customer/** vs admin/**)
│   │       │   │   ├── RateLimitConfig.java
│   │       │   │   └── CorsConfig.java             # CORS for both frontends
│   │       │   ├── filter/
│   │       │   │   ├── JwtValidationFilter.java
│   │       │   │   ├── GrpcTranslationFilter.java  # REST→gRPC translation
│   │       │   │   └── CorrelationIdFilter.java
│   │       │   ├── handler/
│   │       │   │   └── GatewayExceptionHandler.java # gRPC→REST status mapping
│   │       │   └── ApiGatewayApplication.java
│   │       ├── main/resources/
│   │       │   ├── application.yml
│   │       │   └── application-dev.yml
│   │       └── test/java/com/robomart/gateway/
│   │           ├── unit/
│   │           └── integration/
│   │
│   ├── product-service/
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/robomart/product/
│   │       │   ├── config/
│   │       │   │   ├── ElasticsearchConfig.java
│   │       │   │   └── GraphQLConfig.java
│   │       │   ├── controller/
│   │       │   │   ├── ProductRestController.java
│   │       │   │   ├── ProductGraphQLController.java   # @QueryMapping, @BatchMapping
│   │       │   │   └── ProductGrpcService.java         # @GrpcService
│   │       │   ├── dto/
│   │       │   │   ├── CreateProductRequest.java
│   │       │   │   ├── UpdateProductRequest.java
│   │       │   │   ├── ProductDetailResponse.java
│   │       │   │   ├── ProductListResponse.java
│   │       │   │   └── ProductSearchRequest.java
│   │       │   ├── entity/
│   │       │   │   ├── Product.java
│   │       │   │   ├── Category.java
│   │       │   │   └── ProductImage.java
│   │       │   ├── exception/
│   │       │   │   └── ProductNotFoundException.java
│   │       │   ├── mapper/
│   │       │   │   └── ProductMapper.java              # MapStruct
│   │       │   ├── repository/
│   │       │   │   ├── ProductRepository.java          # JPA
│   │       │   │   └── ProductSearchRepository.java    # Elasticsearch
│   │       │   ├── service/
│   │       │   │   ├── ProductService.java
│   │       │   │   └── ProductSearchService.java
│   │       │   ├── event/
│   │       │   │   └── producer/
│   │       │   │       └── ProductEventProducer.java
│   │       │   └── ProductServiceApplication.java
│   │       ├── main/resources/
│   │       │   ├── application.yml
│   │       │   ├── application-dev.yml
│   │       │   ├── application-demo.yml
│   │       │   ├── application-test.yml
│   │       │   ├── graphql/
│   │       │   │   └── schema.graphqls
│   │       │   └── db/
│   │       │       ├── migration/
│   │       │       │   ├── V1__create_products_table.sql
│   │       │       │   ├── V2__create_categories_table.sql
│   │       │       │   ├── V3__create_product_images_table.sql
│   │       │       │   └── V4__create_outbox_events_table.sql
│   │       │       └── seed/
│   │       │           └── R__seed_products.sql
│   │       └── test/java/com/robomart/product/
│   │           ├── unit/
│   │           │   ├── service/
│   │           │   │   └── ProductServiceTest.java
│   │           │   └── mapper/
│   │           │       └── ProductMapperTest.java
│   │           ├── integration/
│   │           │   ├── repository/
│   │           │   │   └── ProductRepositoryIT.java
│   │           │   ├── controller/
│   │           │   │   └── ProductRestControllerIT.java
│   │           │   └── event/
│   │           │       └── ProductEventProducerIT.java
│   │           └── contract/
│   │               └── ProductPactProviderTest.java
│   │
│   ├── cart-service/
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/robomart/cart/
│   │       │   ├── config/
│   │       │   │   └── RedisConfig.java
│   │       │   ├── controller/
│   │       │   │   ├── CartRestController.java
│   │       │   │   └── CartGrpcService.java
│   │       │   ├── dto/
│   │       │   ├── entity/
│   │       │   │   ├── Cart.java                   # Redis hash
│   │       │   │   └── CartItem.java
│   │       │   ├── exception/
│   │       │   │   └── CartExpiredException.java
│   │       │   ├── mapper/
│   │       │   │   └── CartMapper.java
│   │       │   ├── repository/
│   │       │   │   └── CartRepository.java         # Redis repository
│   │       │   ├── service/
│   │       │   │   ├── CartService.java
│   │       │   │   └── CartMergeService.java       # Anonymous → authenticated merge
│   │       │   ├── event/
│   │       │   │   └── consumer/
│   │       │   │       └── ProductEventConsumer.java  # Price/stock updates
│   │       │   └── CartServiceApplication.java
│   │       ├── main/resources/
│   │       │   ├── application.yml
│   │       │   ├── application-dev.yml
│   │       │   └── application-test.yml
│   │       └── test/java/com/robomart/cart/
│   │           ├── unit/
│   │           ├── integration/
│   │           └── contract/
│   │
│   ├── order-service/
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/robomart/order/
│   │       │   ├── config/
│   │       │   ├── controller/
│   │       │   │   ├── OrderRestController.java
│   │       │   │   └── OrderGrpcService.java
│   │       │   ├── dto/
│   │       │   ├── entity/
│   │       │   │   ├── Order.java
│   │       │   │   ├── OrderItem.java
│   │       │   │   ├── OrderStatusHistory.java
│   │       │   │   └── OutboxEvent.java
│   │       │   ├── exception/
│   │       │   │   ├── OrderNotFoundException.java
│   │       │   │   └── OrderAlreadyCancelledException.java
│   │       │   ├── mapper/
│   │       │   │   └── OrderMapper.java
│   │       │   ├── repository/
│   │       │   │   ├── OrderRepository.java
│   │       │   │   └── OutboxEventRepository.java
│   │       │   ├── service/
│   │       │   │   ├── OrderService.java
│   │       │   │   └── OutboxPollingService.java   # 1s interval, batch 50
│   │       │   ├── saga/
│   │       │   │   ├── OrderSagaOrchestrator.java
│   │       │   │   ├── SagaStep.java               # Interface: execute() + compensate()
│   │       │   │   ├── SagaState.java              # Enum: PENDING, INVENTORY_RESERVING, etc.
│   │       │   │   ├── steps/
│   │       │   │   │   ├── ReserveInventoryStep.java
│   │       │   │   │   ├── ProcessPaymentStep.java
│   │       │   │   │   └── ReleaseInventoryStep.java   # Compensation
│   │       │   │   └── SagaAuditLog.java
│   │       │   ├── event/
│   │       │   │   ├── producer/
│   │       │   │   │   └── OrderEventProducer.java
│   │       │   │   └── consumer/
│   │       │   │       ├── InventoryResponseConsumer.java
│   │       │   │       └── PaymentResponseConsumer.java
│   │       │   └── OrderServiceApplication.java
│   │       ├── main/resources/
│   │       │   ├── application.yml
│   │       │   ├── application-dev.yml
│   │       │   ├── application-demo.yml
│   │       │   ├── application-test.yml
│   │       │   └── db/
│   │       │       ├── migration/
│   │       │       │   ├── V1__create_orders_table.sql
│   │       │       │   ├── V2__create_order_items_table.sql
│   │       │       │   ├── V3__create_order_status_history_table.sql
│   │       │       │   ├── V4__create_outbox_events_table.sql
│   │       │       │   └── V5__create_saga_audit_log_table.sql
│   │       │       └── seed/
│   │       │           └── R__seed_orders.sql
│   │       └── test/java/com/robomart/order/
│   │           ├── unit/
│   │           │   ├── service/
│   │           │   └── saga/
│   │           │       └── OrderSagaOrchestratorTest.java
│   │           ├── integration/
│   │           └── contract/
│   │
│   ├── inventory-service/
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/robomart/inventory/
│   │       │   ├── config/
│   │       │   │   └── RedisLockConfig.java        # Distributed lock config
│   │       │   ├── controller/
│   │       │   │   ├── InventoryRestController.java
│   │       │   │   └── InventoryGrpcService.java
│   │       │   ├── dto/
│   │       │   ├── entity/
│   │       │   │   ├── InventoryItem.java
│   │       │   │   ├── StockMovement.java          # Audit trail
│   │       │   │   └── OutboxEvent.java
│   │       │   ├── exception/
│   │       │   │   └── InsufficientStockException.java
│   │       │   ├── mapper/
│   │       │   │   └── InventoryMapper.java
│   │       │   ├── repository/
│   │       │   │   ├── InventoryRepository.java
│   │       │   │   └── StockMovementRepository.java
│   │       │   ├── service/
│   │       │   │   ├── InventoryService.java
│   │       │   │   ├── StockReservationService.java  # Distributed lock logic
│   │       │   │   └── LowStockAlertService.java
│   │       │   ├── event/
│   │       │   │   ├── producer/
│   │       │   │   │   └── InventoryEventProducer.java
│   │       │   │   └── consumer/
│   │       │   │       └── OrderEventConsumer.java    # Reserve/release commands
│   │       │   └── InventoryServiceApplication.java
│   │       ├── main/resources/
│   │       │   ├── application.yml
│   │       │   ├── application-dev.yml
│   │       │   ├── application-demo.yml
│   │       │   ├── application-test.yml
│   │       │   └── db/
│   │       │       ├── migration/
│   │       │       │   ├── V1__create_inventory_items_table.sql
│   │       │       │   ├── V2__create_stock_movements_table.sql
│   │       │       │   └── V3__create_outbox_events_table.sql
│   │       │       └── seed/
│   │       │           └── R__seed_inventory.sql
│   │       └── test/java/com/robomart/inventory/
│   │           ├── unit/
│   │           ├── integration/
│   │           └── contract/
│   │
│   ├── payment-service/
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/robomart/payment/
│   │       │   ├── config/
│   │       │   ├── controller/
│   │       │   │   ├── PaymentRestController.java
│   │       │   │   └── PaymentGrpcService.java
│   │       │   ├── dto/
│   │       │   ├── entity/
│   │       │   │   ├── Payment.java
│   │       │   │   ├── IdempotencyKey.java         # 24h TTL per NFR45
│   │       │   │   └── OutboxEvent.java
│   │       │   ├── exception/
│   │       │   │   └── PaymentDeclinedException.java
│   │       │   ├── mapper/
│   │       │   │   └── PaymentMapper.java
│   │       │   ├── repository/
│   │       │   │   ├── PaymentRepository.java
│   │       │   │   └── IdempotencyKeyRepository.java
│   │       │   ├── service/
│   │       │   │   ├── PaymentService.java
│   │       │   │   └── MockPaymentGateway.java     # Mock external payment
│   │       │   ├── event/
│   │       │   │   ├── producer/
│   │       │   │   │   └── PaymentEventProducer.java
│   │       │   │   └── consumer/
│   │       │   │       └── OrderEventConsumer.java  # Process/refund commands
│   │       │   └── PaymentServiceApplication.java
│   │       ├── main/resources/
│   │       │   ├── application.yml
│   │       │   ├── application-dev.yml
│   │       │   ├── application-test.yml
│   │       │   └── db/
│   │       │       ├── migration/
│   │       │       │   ├── V1__create_payments_table.sql
│   │       │       │   ├── V2__create_idempotency_keys_table.sql
│   │       │       │   └── V3__create_outbox_events_table.sql
│   │       │       └── seed/
│   │       └── test/java/com/robomart/payment/
│   │           ├── unit/
│   │           ├── integration/
│   │           └── contract/
│   │
│   └── notification-service/
│       ├── pom.xml
│       └── src/
│           ├── main/java/com/robomart/notification/
│           │   ├── config/
│           │   │   └── WebSocketConfig.java        # STOMP + SockJS config
│           │   ├── controller/
│           │   │   └── WebSocketController.java    # Admin real-time push
│           │   ├── dto/
│           │   ├── entity/
│           │   │   └── NotificationLog.java
│           │   ├── mapper/
│           │   │   └── NotificationMapper.java
│           │   ├── repository/
│           │   │   └── NotificationLogRepository.java
│           │   ├── service/
│           │   │   ├── NotificationService.java
│           │   │   ├── EmailService.java           # Email notifications
│           │   │   └── AdminPushService.java       # WebSocket to Admin Dashboard
│           │   ├── event/
│           │   │   └── consumer/
│           │   │       ├── OrderEventConsumer.java
│           │   │       ├── InventoryAlertConsumer.java
│           │   │       └── DlqConsumer.java         # DLQ monitoring
│           │   └── NotificationServiceApplication.java
│           ├── main/resources/
│           │   ├── application.yml
│           │   ├── application-dev.yml
│           │   ├── application-test.yml
│           │   └── db/
│           │       └── migration/
│           │           └── V1__create_notification_log_table.sql
│           └── test/java/com/robomart/notification/
│               ├── unit/
│               ├── integration/
│               └── contract/
│
├── frontend/
│   ├── package.json                        # npm workspaces root
│   ├── .eslintrc.cjs                       # Shared ESLint config
│   ├── .prettierrc                         # Shared Prettier config
│   │
│   ├── shared/                             # @robo-mart/shared
│   │   ├── package.json
│   │   ├── tokens/
│   │   │   ├── colors.ts                   # Design system colors
│   │   │   ├── typography.ts               # Font scales
│   │   │   └── spacing.ts                  # Spacing scale
│   │   ├── themes/
│   │   │   └── aura-robomart.ts            # PrimeVue Aura theme preset
│   │   ├── components/
│   │   │   ├── AppToast.vue                # Shared toast wrapper
│   │   │   └── LoadingSkeleton.vue         # Shared skeleton pattern
│   │   ├── composables/
│   │   │   ├── useApiClient.ts             # Axios instance factory
│   │   │   └── useErrorHandler.ts          # Global error handling
│   │   ├── types/
│   │   │   ├── api.ts                      # ApiResponse, ApiErrorResponse, PagedResponse
│   │   │   ├── product.ts                  # Product, Category, ProductImage
│   │   │   ├── cart.ts                     # Cart, CartItem
│   │   │   ├── order.ts                    # Order, OrderItem, OrderStatus
│   │   │   └── auth.ts                     # User, AuthTokens
│   │   └── index.ts                        # Barrel export
│   │
│   ├── customer-website/
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── vite.config.ts
│   │   ├── vitest.config.ts                # Vitest test configuration
│   │   ├── tailwind.config.ts
│   │   ├── index.html
│   │   ├── public/
│   │   │   └── favicon.ico
│   │   └── src/
│   │       ├── App.vue
│   │       ├── main.ts
│   │       ├── api/
│   │       │   ├── client.ts               # Axios instance + interceptors
│   │       │   ├── productApi.ts
│   │       │   ├── cartApi.ts
│   │       │   ├── orderApi.ts
│   │       │   └── authApi.ts
│   │       ├── assets/
│   │       │   └── images/
│   │       ├── components/
│   │       │   ├── common/
│   │       │   │   ├── AppHeader.vue
│   │       │   │   ├── AppFooter.vue
│   │       │   │   ├── AppBreadcrumb.vue
│   │       │   │   └── DegradationBanner.vue   # Service unavailable banner
│   │       │   ├── product/
│   │       │   │   ├── ProductCard.vue
│   │       │   │   ├── ProductCard.spec.ts
│   │       │   │   ├── ProductGrid.vue
│   │       │   │   ├── ProductDetail.vue
│   │       │   │   ├── ProductSearch.vue
│   │       │   │   └── ProductFilters.vue
│   │       │   ├── cart/
│   │       │   │   ├── CartDrawer.vue
│   │       │   │   ├── CartDrawer.spec.ts
│   │       │   │   ├── CartItem.vue
│   │       │   │   └── CartSummary.vue
│   │       │   ├── order/
│   │       │   │   ├── CheckoutForm.vue
│   │       │   │   ├── OrderConfirmation.vue
│   │       │   │   ├── OrderHistory.vue
│   │       │   │   └── OrderStatusTracker.vue
│   │       │   └── auth/
│   │       │       ├── LoginForm.vue
│   │       │       ├── RegisterForm.vue
│   │       │       └── SocialLoginButtons.vue
│   │       ├── composables/
│   │       │   ├── useProductSearch.ts
│   │       │   └── useCart.ts
│   │       ├── layouts/
│   │       │   └── DefaultLayout.vue
│   │       ├── pages/
│   │       │   ├── HomePage.vue
│   │       │   ├── product/
│   │       │   │   ├── ProductListPage.vue
│   │       │   │   └── ProductDetailPage.vue
│   │       │   ├── cart/
│   │       │   │   └── CartPage.vue
│   │       │   ├── order/
│   │       │   │   ├── CheckoutPage.vue
│   │       │   │   ├── OrderConfirmationPage.vue
│   │       │   │   └── OrderHistoryPage.vue
│   │       │   └── auth/
│   │       │       ├── LoginPage.vue
│   │       │       └── RegisterPage.vue
│   │       ├── router/
│   │       │   └── index.ts
│   │       ├── stores/
│   │       │   ├── useAuthStore.ts
│   │       │   ├── useCartStore.ts
│   │       │   ├── useProductStore.ts
│   │       │   ├── useOrderStore.ts
│   │       │   └── useUiStore.ts
│   │       ├── types/
│   │       │   └── index.ts                # Re-export from @robo-mart/shared + local types
│   │       └── utils/
│   │           ├── formatters.ts           # Price, date formatting
│   │           └── validators.ts           # Yup validation schemas
│   │
│   └── admin-dashboard/
│       ├── package.json
│       ├── tsconfig.json
│       ├── vite.config.ts
│       ├── vitest.config.ts                # Vitest test configuration
│       ├── tailwind.config.ts
│       ├── index.html
│       └── src/
│           ├── App.vue
│           ├── main.ts
│           ├── api/
│           │   ├── client.ts
│           │   ├── productApi.ts
│           │   ├── inventoryApi.ts
│           │   ├── orderApi.ts
│           │   └── websocketClient.ts      # SockJS + STOMP.js
│           ├── components/
│           │   ├── common/
│           │   │   ├── AdminSidebar.vue
│           │   │   ├── AdminHeader.vue
│           │   │   └── StatCard.vue
│           │   ├── dashboard/
│           │   │   ├── LiveOrderFeed.vue    # WebSocket real-time
│           │   │   ├── SystemHealthPanel.vue
│           │   │   └── MetricsChart.vue
│           │   ├── product/
│           │   │   ├── ProductTable.vue
│           │   │   ├── ProductForm.vue
│           │   │   └── ProductImageUpload.vue
│           │   ├── inventory/
│           │   │   ├── InventoryTable.vue
│           │   │   ├── StockAdjustForm.vue
│           │   │   └── LowStockAlerts.vue
│           │   ├── order/
│           │   │   ├── OrderTable.vue
│           │   │   ├── OrderDetail.vue
│           │   │   └── OrderStatusFilter.vue
│           │   ├── auth/
│           │   │   └── AdminLoginRedirect.vue  # Keycloak redirect for admin login
│           │   └── system/
│           │       ├── DlqManager.vue       # DLQ monitoring + replay
│           │       └── ReportingPanel.vue   # CQRS reports
│           ├── composables/
│           │   └── useWebSocket.ts
│           ├── layouts/
│           │   └── AdminLayout.vue
│           ├── pages/
│           │   ├── DashboardPage.vue
│           │   ├── product/
│           │   ├── inventory/
│           │   ├── order/
│           │   ├── auth/
│           │   │   └── AdminLoginPage.vue
│           │   └── system/
│           ├── router/
│           │   └── index.ts                # Includes admin role guard
│           ├── stores/
│           │   ├── useAdminStore.ts
│           │   ├── useAuthStore.ts          # Admin-specific auth (Keycloak redirect)
│           │   ├── useWebSocketStore.ts
│           │   ├── useProductStore.ts
│           │   ├── useInventoryStore.ts
│           │   └── useOrderStore.ts
│           ├── types/
│           └── utils/
│
├── infra/
│   ├── docker/
│   │   ├── .env.example                    # All required env vars documented
│   │   ├── docker-compose.yml              # Core profile (12 containers)
│   │   ├── docker-compose.full.yml         # Full profile override (+6 observability)
│   │   ├── api-gateway/
│   │   │   └── Dockerfile
│   │   ├── product-service/
│   │   │   └── Dockerfile                  # Multi-stage: build + runtime
│   │   ├── cart-service/
│   │   │   └── Dockerfile
│   │   ├── order-service/
│   │   │   └── Dockerfile
│   │   ├── inventory-service/
│   │   │   └── Dockerfile
│   │   ├── payment-service/
│   │   │   └── Dockerfile
│   │   ├── notification-service/
│   │   │   └── Dockerfile
│   │   ├── keycloak/
│   │   │   └── robomart-realm.json         # Pre-configured realm export
│   │   ├── pact-broker/
│   │   │   └── docker-compose.pact.yml
│   │   └── grafana/
│   │       ├── provisioning/
│   │       │   ├── datasources/
│   │       │   │   └── datasources.yml     # Prometheus, Loki, Tempo
│   │       │   └── dashboards/
│   │       │       └── dashboards.yml
│   │       └── dashboards/
│   │           ├── service-overview.json
│   │           └── saga-monitoring.json
│   ├── k8s/
│   │   ├── base/
│   │   │   ├── namespace.yml
│   │   │   └── configmap.yml
│   │   └── services/
│   │       ├── api-gateway/
│   │       │   ├── deployment.yml
│   │       │   ├── service.yml
│   │       │   └── hpa.yml
│   │       ├── product-service/
│   │       ├── cart-service/
│   │       ├── order-service/
│   │       ├── inventory-service/
│   │       ├── payment-service/
│   │       └── notification-service/
│   └── ci/
│       └── scripts/
│           ├── schema-compat-check.sh      # Avro + Protobuf backward compat
│           └── selective-build.sh          # Maven selective module build
│
└── docs/
    ├── architecture.md                     # → symlink or copy from _bmad-output
    ├── adr/
    │   ├── 000-template.md                 # ADR template for consistency
    │   └── 001-saga-custom-vs-temporal.md
    └── runbook/
        ├── local-dev-setup.md
        └── docker-compose-profiles.md
```

**Shared Proto Types Rule:** Only types used by 3+ services belong in `common/types.proto` (e.g., `Money`, `Address`). Types used by only 2 services are defined inline in the consuming service's proto file to avoid unnecessary coupling and recompilation.

### Architectural Boundaries

**Service Boundaries (Database-per-Service):**

| Service | Database | Owns | Communicates Via |
|---------|----------|------|-----------------|
| Product Service | PostgreSQL `product_db` + Elasticsearch | Products, Categories, Images | REST, GraphQL, gRPC (provider), Kafka (producer) |
| Cart Service | Redis | Carts, CartItems | REST, gRPC (provider + consumer to Product) |
| Order Service | PostgreSQL `order_db` | Orders, OrderItems, SagaState, Outbox | REST, gRPC (consumer to Inventory/Payment), Kafka (producer + consumer) |
| Inventory Service | PostgreSQL `inventory_db` + Redis (locks) | InventoryItems, StockMovements, Outbox | REST, gRPC (provider), Kafka (producer + consumer) |
| Payment Service | PostgreSQL `payment_db` | Payments, IdempotencyKeys, Outbox | REST, gRPC (provider), Kafka (producer + consumer) |
| Notification Service | PostgreSQL `notification_db` | NotificationLogs | Kafka (consumer only), WebSocket (producer to Admin) |
| API Gateway | None | — | REST (external), gRPC (internal consumer), WebSocket (proxy) |

**Data Access Rules:**
- Services NEVER access another service's database directly
- Cross-service data: via gRPC (sync) or Kafka events (async)
- Shared data (user context): propagated via headers/metadata, not DB queries

**API Gateway Route Boundaries:**

| Route Prefix | Target | Auth Required | Role |
|-------------|--------|---------------|------|
| `/api/v1/products/**` | Product Service | No (public browse) | — |
| `/api/v1/cart/**` | Cart Service | Optional (anonymous cart supported) | — |
| `/api/v1/orders/**` | Order Service | Yes | CUSTOMER |
| `/api/v1/auth/**` | Keycloak | No | — |
| `/api/v1/admin/products/**` | Product Service | Yes | ADMIN |
| `/api/v1/admin/inventory/**` | Inventory Service | Yes | ADMIN |
| `/api/v1/admin/orders/**` | Order Service | Yes | ADMIN |
| `/api/v1/admin/reports/**` | Order Service (CQRS) | Yes | ADMIN |
| `/api/v1/admin/dlq/**` | Notification Service | Yes | ADMIN |
| `/ws/**` | Notification Service | Yes (STOMP CONNECT) | ADMIN |
| `/graphql` | Product Service | No | — |

**Frontend Boundaries:**

| App | Scope | API Access | Auth Flow |
|-----|-------|------------|-----------|
| Customer Website | Browse, search, cart, checkout, order history | REST via API Gateway | Keycloak redirect (customer realm) |
| Admin Dashboard | Product CRUD, inventory, orders, reports, DLQ, real-time | REST + WebSocket via API Gateway | Keycloak redirect (admin realm, role guard) |
| @robo-mart/shared | Design tokens, shared types, shared composables | No direct API access | — |

### Requirements to Structure Mapping

**FR Category → Service/Component Mapping:**

| FR Domain | Primary Service | Frontend | Key Files |
|-----------|----------------|----------|-----------|
| Product Discovery (FR1-FR8) | product-service | customer-website/product/ | `ProductSearchService`, `ProductGraphQLController`, `ProductSearch.vue` |
| Cart Management (FR9-FR13) | cart-service | customer-website/cart/ | `CartService`, `CartMergeService`, `CartDrawer.vue` |
| Order Processing (FR14-FR19) | order-service | customer-website/order/ | `OrderSagaOrchestrator`, `CheckoutForm.vue` |
| Order Cancellation (FR20-FR22) | order-service | customer-website/order/ | `OrderSagaOrchestrator` (compensation), `OrderRestController` (cancel endpoint), `OrderStatusTracker.vue` |
| Payment (FR23) | payment-service | — (via Saga) | `PaymentService`, `MockPaymentGateway` |
| Admin Product CRUD (FR24-FR26) | product-service | admin-dashboard/product/ | `ProductRestController`, `ProductForm.vue` |
| Auth & Accounts (FR37-FR42) | API Gateway + Keycloak | customer-website/auth/, admin-dashboard/auth/ | `JwtValidationFilter`, `LoginForm.vue`, `AdminLoginRedirect.vue` |
| Admin Inventory (FR43-FR46) | inventory-service | admin-dashboard/inventory/ | `InventoryRestController`, `InventoryTable.vue` |
| Admin Orders (FR47-FR49) | order-service | admin-dashboard/order/ | `OrderRestController`, `OrderTable.vue` |
| System Patterns (FR50-FR72) | All services + common-lib | — | `GlobalExceptionHandler`, `TracingConfig`, `OutboxPollingService` |
| Real-time Monitoring (FR70) | notification-service | admin-dashboard/dashboard/ | `AdminPushService`, `LiveOrderFeed.vue` |
| DLQ Management (FR74) | notification-service | admin-dashboard/system/ | `DlqConsumer`, `DlqManager.vue` |

**Cross-Cutting Concern → Module Mapping:**

| Concern | Module | Key Files |
|---------|--------|-----------|
| Exception handling | common-lib | `GlobalExceptionHandler`, `ErrorCode`, exception hierarchy |
| JSON serialization | common-lib | `JacksonConfig` |
| Structured logging | common-lib | `LoggingConfig`, `logback-spring.xml` |
| Trace propagation | common-lib | `TracingConfig` (gRPC + Kafka interceptors) |
| Code style enforcement | backend/config | `checkstyle.xml`, `.editorconfig` |
| JWT/Security | security-lib | `SecurityConfig`, `JwtAuthenticationFilter` |
| gRPC contracts | proto | `*.proto` files + generated stubs |
| Event schemas | events | `*.avsc` Avro schemas |
| Test infrastructure | test-support | Container configs, TestData builders, annotations |

### Integration Points

**Kafka Topic → Producer/Consumer Mapping:**

| Topic | Producer | Consumer(s) |
|-------|----------|-------------|
| `product.product.created` | Product Service | Notification Service |
| `product.product.updated` | Product Service | Cart Service (price update), Notification Service |
| `product.product.deleted` | Product Service | Cart Service, Notification Service |
| `order.order.created` | Order Service | Inventory Service (reserve), Notification Service |
| `order.order.status-changed` | Order Service | Notification Service → Admin Dashboard (WebSocket) |
| `order.order.cancelled` | Order Service | Inventory Service (release), Payment Service (refund), Notification Service |
| `inventory.stock.reserved` | Inventory Service | Order Service (saga continue) |
| `inventory.stock.released` | Inventory Service | Order Service (saga acknowledge) |
| `inventory.stock.low-alert` | Inventory Service | Notification Service → Admin Dashboard |
| `payment.payment.processed` | Payment Service | Order Service (saga continue) |
| `payment.payment.refunded` | Payment Service | Order Service (saga acknowledge), Notification Service |

**gRPC Service → Consumer Mapping:**

| gRPC Service | Provider | Consumer(s) |
|-------------|----------|-------------|
| `ProductService` | product-service | API Gateway (REST→gRPC), cart-service (price lookup) |
| `InventoryService` | inventory-service | API Gateway, order-service (Saga sync check) |
| `PaymentService` | payment-service | order-service (Saga sync call) |
| `OrderService` | order-service | API Gateway |
| `CartService` | cart-service | API Gateway |

### Data Flow

**Order Creation Flow (Saga):**

```
Customer Website → API Gateway (REST) → Order Service (gRPC)
    → Order Service creates order (DB) + outbox event
    → Outbox poller publishes to Kafka: order.order.created
    → Inventory Service consumes → reserves stock (distributed lock)
    → Inventory Service publishes: inventory.stock.reserved
    → Order Service consumes → triggers Payment step
    → Payment Service consumes → processes payment (mock)
    → Payment Service publishes: payment.payment.processed
    → Order Service consumes → marks order CONFIRMED
    → Order Service publishes: order.order.status-changed
    → Notification Service consumes → pushes WebSocket to Admin Dashboard
```

**Cache Invalidation Flow:**

```
Product Service updates product (DB) + outbox event
    → Outbox poller publishes: product.product.updated
    → Cart Service consumes → updates cached price in Redis cart
    → Product Service (self) invalidates local cache for updated product
```

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
All technology choices verified compatible: Spring Boot 4.0.4 + Spring Cloud 2025.1 + Spring gRPC 1.0.x + Spring for GraphQL (all official Spring ecosystem). Java 25 LTS + Maven + Checkstyle + MapStruct toolchain coherent. Vue.js 3 + Vite 8 + PrimeVue 4.3.9 + Tailwind + Pinia + TypeScript frontend stack coherent. Avro + Schema Registry + Kafka serialization chain consistent. OpenTelemetry + Tempo + Loki + Prometheus + Grafana observability stack unified. No contradictory decisions found.

**Pattern Consistency:**
Naming conventions (`camelCase` JSON, `snake_case` DB, `PascalCase` Java/Vue) have no cross-layer conflicts. API response wrapper applied consistently to REST, explicitly excluded from GraphQL. Error handling chain (exception hierarchy → `@ControllerAdvice` → error wrapper) consistent across all services. Kafka event envelope standardized with Avro schema.

**Structure Alignment:**
Every service follows identical directory layout. Test structure mirrors source with `unit/integration/contract` separation. Frontend apps follow identical structure with co-located test files. Shared modules have clear single-responsibility boundaries.

**Resolved During Validation:**
- Outbox polling was only defined in Order Service but Inventory and Payment services also have outbox tables. **Resolution:** Each service with an outbox table has its own `OutboxPollingService` (same pattern: 1s interval, batch 50, 7-day cleanup).

### Requirements Coverage Validation ✅

**Functional Requirements Coverage: 74/74 FRs**

| FR Range | Domain | Status |
|----------|--------|--------|
| FR1-FR8 | Product Discovery | ✅ Product Service + Elasticsearch + GraphQL |
| FR9-FR13 | Cart Management | ✅ Cart Service + Redis + merge flow |
| FR14-FR19 | Order Processing | ✅ Order Service + Saga orchestration |
| FR20-FR22 | Order Cancellation | ✅ Saga compensation path explicitly mapped |
| FR23 | Payment | ✅ Payment Service + MockPaymentGateway |
| FR24-FR26 | Admin Product CRUD | ✅ Product Service + Admin Dashboard |
| FR37-FR42 | Auth & Accounts | ✅ Keycloak + API Gateway + security-lib |
| FR43-FR46 | Admin Inventory | ✅ Inventory Service + Admin Dashboard |
| FR47-FR49 | Admin Orders | ✅ Order Service + Admin Dashboard |
| FR50-FR72 | System Patterns | ✅ common-lib + distributed patterns |
| FR66 | API Response Aggregation | ✅ API Gateway `CompositeFilter` |
| FR67 | Event Sourcing | ✅ Outbox Pattern + audit trail (sufficient per PRD validation) |
| FR70 | Real-time Monitoring | ✅ Notification Service + WebSocket |
| FR74 | DLQ Management | ✅ Notification Service + Admin Dashboard |

**Non-Functional Requirements Coverage: 62/62 NFRs**

| NFR Category | Status | Architecture Support |
|-------------|--------|---------------------|
| Performance (NFR1-10) | ✅ | gRPC, Redis caching, connection pooling, lock granularity |
| Security (NFR11-18) | ✅ | mTLS, JWT propagation, Keycloak, K8s Secrets, RBAC |
| Scalability (NFR19-25) | ✅ | K8s HPA, Kafka partitions, stateless, database-per-service |
| Reliability (NFR26-37) | ✅ | Circuit Breaker, Outbox, DLQ, graceful shutdown |
| Observability (NFR38-42) | ✅ | OpenTelemetry, correlation IDs, structured logging |
| Data Consistency (NFR43-45) | ✅ | Outbox replay, Saga compensation, idempotency keys |
| Code Quality (NFR46-48) | ✅ | ArchUnit, OpenAPI drift, buf lint, Checkstyle |
| Dev & Deploy (NFR49-62) | ✅ | Maven selective builds, Testcontainers, Pact, GitHub Actions |

**NFR Notes:**
- NFR55 (Chaos testing): Architecture includes chaos testing in implementation sequence (step 11). Tool selection deferred to Phase 4 — recommended: Chaos Monkey for Spring Boot.
- NFR25 (K8s resource limits): Operational config implemented via K8s manifests in `infra/k8s/services/`.

### Implementation Readiness Validation ✅

**Decision Completeness: HIGH**
- All critical decisions have specific versions (Spring Boot 4.0.4, Java 25, PrimeVue 4.3.9, etc.)
- All deferred decisions explicitly documented (CQRS read model, image storage, rate limiting, chaos testing)
- Phased Saga approach has clear Phase A/B split with scope boundaries

**Structure Completeness: HIGH**
- 200+ files explicitly defined in project tree
- Every service, shared module, and frontend app has complete directory structure
- Integration points (Kafka topics, gRPC services, API routes) fully mapped with producer/consumer tables

**Pattern Completeness: HIGH**
- 38 conflict points identified and resolved with concrete examples
- 14 enforcement guidelines for AI agents with tooling support
- Good/bad code examples provided for key patterns
- Testing conventions (naming, assertions, data builders, containers) all specified

### Gap Analysis Results

**Critical Gaps: NONE**

**Important Gaps (Resolved):**

| Gap | Resolution |
|-----|------------|
| Outbox polling missing in Inventory + Payment | Added `OutboxPollingService` to both — same pattern as Order Service |
| FR66 API aggregation pattern undefined | Defined: API Gateway `CompositeFilter` for response aggregation |

**Deferred Decisions (Intentional — Not Gaps):**

| Decision | Deferred To | Default |
|----------|-------------|---------|
| Chaos testing tool | Phase 4 implementation | Chaos Monkey for Spring Boot (recommended) |
| CQRS read model storage | When PostgreSQL views prove insufficient | PostgreSQL views (initial) |
| Image storage | When local filesystem proves insufficient | Local filesystem → S3/MinIO migration path |
| Rate limiting algorithm | Production traffic analysis | Token Bucket (Spring Cloud Gateway default), 100 req/min/user |

### Architecture Completeness Checklist

**✅ Requirements Analysis**
- [x] Project context thoroughly analyzed (74 FRs, 62 NFRs)
- [x] Scale and complexity assessed (HIGH — 7 services, 5 protocols)
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped (12 concerns)
- [x] NFR tension pairs identified and resolved (3 pairs)
- [x] Architectural risk register documented (5 risks)

**✅ Architectural Decisions**
- [x] Critical decisions documented with verified versions
- [x] Technology stack fully specified (backend + frontend + infrastructure)
- [x] Integration patterns defined (gRPC, Kafka, GraphQL, WebSocket, REST)
- [x] Performance considerations addressed (caching, connection pooling, mTLS)
- [x] Security architecture defined (Keycloak, JWT propagation, mTLS, RBAC)
- [x] Saga orchestration designed (phased approach)
- [x] Contract testing strategy per protocol (Pact REST, Protobuf gRPC, Schema Registry Kafka)

**✅ Implementation Patterns**
- [x] Naming conventions established (DB, API, gRPC, Kafka, Java, Vue/TS)
- [x] Structure patterns defined (backend service, frontend app, test)
- [x] Communication patterns specified (Kafka envelope, Outbox, Pinia stores)
- [x] Process patterns documented (error handling, logging, loading states, validation)
- [x] Testing conventions defined (naming, AssertJ, TestData builders, Testcontainers)
- [x] Enforcement guidelines documented (14 rules + tooling)

**✅ Project Structure**
- [x] Complete directory structure defined (200+ files)
- [x] Component boundaries established (database-per-service, API Gateway routes)
- [x] Integration points mapped (Kafka topics, gRPC services, REST routes)
- [x] Requirements to structure mapping complete (FR → service/component → file)
- [x] Data flow documented (Saga flow, cache invalidation flow)

### Architecture Readiness Assessment

**Overall Status: READY FOR IMPLEMENTATION**

**Confidence Level: HIGH**

**Key Strengths:**
1. Every decision has a verified version number — no "latest" ambiguity
2. 38 AI agent conflict points identified and resolved with concrete examples
3. Complete Kafka topic → producer/consumer mapping eliminates integration guesswork
4. Phased Saga approach manages complexity without sacrificing learning depth
5. Docker Compose profiles (`core` vs `full`) enable practical development on varied hardware
6. Party Mode reviews (4 rounds) caught 30+ improvements preventing implementation conflicts
7. NFR tension pairs explicitly resolved with architectural strategies

**Areas for Future Enhancement:**
1. Chaos testing tool selection (deferred to Phase 4)
2. CQRS read model may need dedicated store if PostgreSQL views prove insufficient
3. Image storage migration path (local → S3/MinIO) when needed
4. Rate limiting algorithm tuning based on production traffic patterns

### Implementation Handoff

**AI Agent Guidelines:**
- Follow ALL architectural decisions exactly as documented in this file
- Use implementation patterns consistently — refer to the Enforcement Guidelines section
- Respect project structure and boundaries — files go in prescribed locations
- Use the Naming Patterns section as the authoritative reference for all naming decisions
- Follow the Testing Conventions for all test code
- When in doubt, check this document first — it is the single source of architectural truth

**First Implementation Priority:**
1. Initialize Maven multi-module project with parent POM + all shared modules
2. Set up Docker Compose (`core` profile) with PostgreSQL, Redis, Kafka, Elasticsearch, Keycloak, Schema Registry
3. Create `common-lib`, `security-lib`, `proto`, `events`, `test-support` module structures
4. First vertical slice: Product Service + Customer Website search/browse
