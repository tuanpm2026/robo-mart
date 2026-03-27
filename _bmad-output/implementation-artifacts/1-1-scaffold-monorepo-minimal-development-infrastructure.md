# Story 1.1: Scaffold Monorepo & Minimal Development Infrastructure

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want a scaffolded monorepo with Maven multi-module backend, npm workspaces frontend, common-lib foundation, and Docker Compose with minimal containers,
so that I can begin developing the first vertical slice (Product Service) with all foundational tooling in place.

## Acceptance Criteria

1. **AC1 — Docker Compose minimal containers**: Given a new repository, when I run `docker compose --profile core up`, then only 5 containers start: PostgreSQL (product_db), Elasticsearch, Kafka (KRaft), Schema Registry, and Kafka UI (optional) — no Redis, no Keycloak, no additional PostgreSQL instances yet. Total memory ~3-4GB.

2. **AC2 — Maven multi-module compiles**: Given the Maven multi-module project, when I run `./mvnw compile` from `backend/`, then parent POM (with spring-boot-starter-parent, Spring Cloud BOM, maven-enforcer-plugin) and all declared modules compile without errors. Maven Wrapper (mvnw) is included.

3. **AC3 — common-lib provides shared foundation**: Given common-lib module, when referenced by a service module, then it provides: ApiResponse, ApiErrorResponse, PagedResponse DTOs, PaginationMeta, ErrorCode enum, exception hierarchy (RoboMartException → ResourceNotFoundException, BusinessRuleException, ValidationException, ExternalServiceException), GlobalExceptionHandler (@ControllerAdvice), JacksonConfig (camelCase, NON_NULL, ISO dates), LoggingConfig, logback-spring.xml (structured JSON), BaseEntity (id, createdAt, updatedAt), CorrelationIdFilter.

4. **AC4 — events module with base Avro schema**: Given events module, when compiled, then it contains only the base Avro event envelope schema (eventId, eventType, aggregateId, aggregateType, timestamp, version, payload) — domain-specific schemas added by later stories.

5. **AC5 — Placeholder modules exist**: Given security-lib, proto, and test-support modules, when inspected, then each has a valid pom.xml and empty package structure — no implementation content yet.

6. **AC6 — Frontend npm workspaces resolve**: Given frontend/ directory, when I run `npm install`, then npm workspaces resolve: @robo-mart/shared (empty package structure with package.json), customer-website (placeholder), admin-dashboard (placeholder).

7. **AC7 — Project config files exist**: Given the project root, when inspected, then .editorconfig, .gitignore, backend/config/checkstyle/checkstyle.xml, and infra/docker/ directory exist.

## Tasks / Subtasks

- [ ] Task 1: Create project root structure (AC: #7)
  - [ ] 1.1 Create root directory layout: `backend/`, `frontend/`, `infra/docker/`, `infra/k8s/`, `infra/ci/`, `docs/`
  - [ ] 1.2 Create `.editorconfig` (indent_style=space, indent_size=2 for yaml/json/js/ts, indent_size=4 for java)
  - [ ] 1.3 Create `.gitignore` (Java: target/, *.class, *.jar; Node: node_modules/, dist/; IDE: .idea/, .vscode/; OS: .DS_Store; Docker: docker volumes; Env: .env*)
  - [ ] 1.4 Create `backend/config/checkstyle/checkstyle.xml` (Google Java Style base with project-specific overrides)

- [ ] Task 2: Create Maven multi-module backend (AC: #2)
  - [ ] 2.1 Create `backend/pom.xml` parent POM:
    - Parent: `spring-boot-starter-parent:4.0.4`
    - Properties: `java.version=25`, spring-cloud.version=`2025.1.1`
    - dependencyManagement: Spring Cloud BOM, MapStruct, Avro, etc.
    - pluginManagement: spring-boot-maven-plugin, avro-maven-plugin, protobuf-maven-plugin, flyway-maven-plugin, maven-enforcer-plugin
    - maven-enforcer-plugin rules: requireJavaVersion(25), dependencyConvergence, ban commons-logging, ban junit-vintage-engine
    - modules: common-lib, security-lib, proto, events, test-support, product-service
  - [ ] 2.2 Add Maven Wrapper: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
  - [ ] 2.3 Verify `./mvnw compile` passes from `backend/`

- [ ] Task 3: Implement common-lib module (AC: #3)
  - [ ] 3.1 Create `backend/common-lib/pom.xml` (dependencies: spring-boot-starter-web, jackson, logback, servlet-api)
  - [ ] 3.2 Package: `com.robomart.common`
  - [ ] 3.3 DTOs: `ApiResponse<T>` (`data`, `traceId`), `ApiErrorResponse` (`error: {code, message, details}`, `traceId`, `timestamp`), `PagedResponse<T>` (`data`, `pagination: PaginationMeta`), `PaginationMeta` (`page`, `size`, `totalElements`, `totalPages`)
  - [ ] 3.4 `ErrorCode` enum: RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION, VALIDATION_ERROR, EXTERNAL_SERVICE_ERROR, INTERNAL_ERROR
  - [ ] 3.5 Exception hierarchy:
    - `RoboMartException` (abstract, extends RuntimeException) with errorCode, message
    - `ResourceNotFoundException` (→ 404)
    - `BusinessRuleException` (→ 409)
    - `ValidationException` (→ 400)
    - `ExternalServiceException` (→ 503)
  - [ ] 3.6 `GlobalExceptionHandler` (@ControllerAdvice): maps each exception to ApiErrorResponse with traceId from OpenTelemetry span. WARN for 4xx, ERROR for 5xx. No stack traces in response.
  - [ ] 3.7 `JacksonConfig` (@Configuration): camelCase property naming, NON_NULL serialization, ISO-8601 dates, JavaTimeModule
  - [ ] 3.8 `LoggingConfig` + `logback-spring.xml`: structured JSON format with fields: timestamp (ISO-8601), level, service (from spring.application.name), traceId, correlationId, logger, message, context. NEVER log passwords, JWT tokens, PII.
  - [ ] 3.9 `BaseEntity` (@MappedSuperclass): `id` (Long, @GeneratedValue IDENTITY), `createdAt` (Instant, @CreationTimestamp), `updatedAt` (Instant, @UpdateTimestamp)
  - [ ] 3.10 `CorrelationIdFilter` (OncePerRequestFilter): reads X-Correlation-Id header (or generates UUID), sets on MDC, adds to response header

- [ ] Task 4: Create events module with base Avro schema (AC: #4)
  - [ ] 4.1 Create `backend/events/pom.xml` (dependencies: avro, avro-maven-plugin for code generation)
  - [ ] 4.2 Create base schema: `events/src/main/avro/base_event_envelope.avsc`
    ```json
    {
      "type": "record",
      "name": "BaseEventEnvelope",
      "namespace": "com.robomart.events",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "eventType", "type": "string"},
        {"name": "aggregateId", "type": "string"},
        {"name": "aggregateType", "type": "string"},
        {"name": "timestamp", "type": "long", "logicalType": "timestamp-millis"},
        {"name": "version", "type": "int"},
        {"name": "payload", "type": "string"}
      ]
    }
    ```
  - [ ] 4.3 Configure avro-maven-plugin to generate Java classes from .avsc files
  - [ ] 4.4 Verify `./mvnw compile -pl :events` generates Avro classes

- [ ] Task 5: Create placeholder modules (AC: #5)
  - [ ] 5.1 `backend/security-lib/pom.xml` — empty `com.robomart.security` package with placeholder class
  - [ ] 5.2 `backend/proto/pom.xml` — empty, will hold .proto files in Epic 4
  - [ ] 5.3 `backend/test-support/pom.xml` — empty `com.robomart.test` package with placeholder class

- [ ] Task 6: Create product-service skeleton (AC: #2)
  - [ ] 6.1 `backend/product-service/pom.xml` (depends on common-lib, events)
  - [ ] 6.2 Main class: `ProductServiceApplication` with @SpringBootApplication
  - [ ] 6.3 Package structure (empty packages): `com.robomart.product.{config, controller, dto, entity, exception, mapper, repository, service, event}`
  - [ ] 6.4 `application.yml` with spring.application.name=product-service, server.port=8081, Spring profiles (dev, demo, test)
  - [ ] 6.5 Verify service starts (even without DB connection at this point)

- [ ] Task 7: Create Docker Compose for minimal infrastructure (AC: #1)
  - [ ] 7.1 Create `infra/docker/docker-compose.yml` with `core` profile:
    - **PostgreSQL** (product_db): image postgres:17, port 5432, env POSTGRES_DB=product_db, POSTGRES_USER=robomart, POSTGRES_PASSWORD=robomart, memory limit 512MB
    - **Elasticsearch**: image elasticsearch:8.17.x (single-node), port 9200, discovery.type=single-node, xpack.security.enabled=false, memory limit 1GB, ES_JAVA_OPTS=-Xms512m -Xmx512m
    - **Kafka (KRaft)**: image confluentinc/cp-kafka:7.9.x, port 9092, KRaft mode (no Zookeeper), KAFKA_PROCESS_ROLES=broker,controller, memory limit 1GB
    - **Schema Registry**: image confluentinc/cp-schema-registry:7.9.x, port 8081→use 8085 to avoid conflict with product-service, SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS
    - **Kafka UI** (optional): image provectuslabs/kafka-ui:latest, port 8080→use 9090
  - [ ] 7.2 Create Docker network `robomart-network`
  - [ ] 7.3 Create `infra/docker/.env` with configurable ports and credentials
  - [ ] 7.4 Verify `docker compose --profile core up -d` starts all 5 containers, total RAM ~3-4GB
  - [ ] 7.5 Verify product-service can connect to PostgreSQL when both are running

- [ ] Task 8: Create frontend npm workspaces (AC: #6)
  - [ ] 8.1 Create `frontend/package.json` with workspaces: ["shared", "customer-website", "admin-dashboard"]
  - [ ] 8.2 Create `frontend/shared/package.json` (name: @robo-mart/shared, empty src/ structure with tokens/, themes/, components/ dirs)
  - [ ] 8.3 Scaffold `frontend/customer-website/` with create-vue (TypeScript, Vue Router, Pinia, Vitest, ESLint+Prettier)
  - [ ] 8.4 Scaffold `frontend/admin-dashboard/` with create-vue (same options)
  - [ ] 8.5 Verify `npm install` from `frontend/` resolves all workspaces
  - [ ] 8.6 Verify `npm run dev -w customer-website` starts dev server

## Dev Notes

### Architecture Compliance

- **Monorepo structure** must match architecture doc exactly — see Project Structure Notes below
- **Maven parent POM** inherits from `spring-boot-starter-parent:4.0.4` — do NOT use spring-boot-dependencies BOM separately
- **Spring Cloud BOM** version `2025.1.1` (Oakwood) declared in dependencyManagement
- **Java 25 LTS** is the target — fallback to Java 21 only if third-party compatibility issues
- **Spring Framework 7.x** is required by Spring Boot 4 — it comes automatically via spring-boot-starter-parent
- **Jakarta EE 11** baseline — all javax.* imports must be jakarta.* (Spring Boot 4 requirement)

### Technology Versions (Verified March 2026)

| Technology | Version | Notes |
|-----------|---------|-------|
| Java | 25 LTS | Fallback: 21 LTS |
| Spring Boot | 4.0.4 | Latest stable (March 19, 2026) |
| Spring Cloud | 2025.1.1 (Oakwood) | Latest GA |
| Spring Framework | 7.x | Required by Spring Boot 4 |
| Maven Wrapper | 3.9.x | Include mvnw + .mvn/ |
| Vue.js | 3.5.x / 3.6.x | Via create-vue |
| create-vue | 3.18.5 | Includes Vite 8 |
| PrimeVue | 4.3.9 | NOT installed in this story — Story 1.6 |
| Vite | 8.x | Bundled via create-vue. Uses Rolldown bundler (replaces esbuild/Rollup) |
| Tailwind CSS | 4.x | NOT installed in this story — Story 1.6. Note: v4 uses CSS @theme directives instead of tailwind.config.js |
| PostgreSQL | 17.x | Docker image |
| Elasticsearch | 8.17.x | Docker image, single-node |
| Kafka | 7.9.x (Confluent) | KRaft mode, no Zookeeper |
| Schema Registry | 7.9.x (Confluent) | Confluent Platform 8.2 compatible |
| MapStruct | Latest stable | Declared in parent POM dependencyManagement only — used from Story 1.2 |

### Critical Guardrails — DO NOT

- **DO NOT** install Redis, Keycloak, or additional PostgreSQL instances — those come in later epics
- **DO NOT** install PrimeVue, Tailwind CSS, or design tokens — that's Story 1.6
- **DO NOT** implement any Product Service business logic (controllers, entities, repositories) — that's Story 1.2
- **DO NOT** create Flyway migrations — that's Story 1.2
- **DO NOT** configure Spring Security or JWT — that's Epic 3
- **DO NOT** implement the Outbox Pattern — that's Story 1.3
- **DO NOT** add observability stack (Grafana, Prometheus, Loki, Tempo) — that's Epic 9
- **DO NOT** create CI/CD pipelines — that's Epic 10

### common-lib Implementation Details

**REST API Response Wrapper format:**
```java
// Success response
public record ApiResponse<T>(T data, String traceId) {}

// Error response
public record ApiErrorResponse(ErrorDetail error, String traceId, Instant timestamp) {}
public record ErrorDetail(String code, String message, Map<String, String> details) {}

// Paginated response
public record PagedResponse<T>(List<T> data, PaginationMeta pagination, String traceId) {}
public record PaginationMeta(int page, int size, long totalElements, int totalPages) {}
```

**GlobalExceptionHandler must:**
- Extract traceId from current OpenTelemetry span (or "no-trace" if not available)
- Map ResourceNotFoundException → 404, BusinessRuleException → 409, ValidationException → 400, ExternalServiceException → 503
- Unhandled exceptions → 500 with generic message (never expose stack trace)
- Log WARN for 4xx, ERROR for 5xx — always include traceId

**Structured JSON log format (logback-spring.xml):**
```json
{
  "timestamp": "2026-03-27T10:30:00.123Z",
  "level": "INFO",
  "service": "product-service",
  "traceId": "abc123",
  "correlationId": "req-456",
  "logger": "c.r.product.service.ProductService",
  "message": "Product created successfully",
  "context": {}
}
```

### Avro Schema — events module

The base event envelope uses Avro with Confluent Schema Registry. The avro-maven-plugin generates Java classes at compile time.

Schema location: `events/src/main/avro/base_event_envelope.avsc`

Configure `avro-maven-plugin` in events/pom.xml:
```xml
<plugin>
  <groupId>org.apache.avro</groupId>
  <artifactId>avro-maven-plugin</artifactId>
  <executions>
    <execution>
      <phase>generate-sources</phase>
      <goals><goal>schema</goal></goals>
      <configuration>
        <sourceDirectory>${project.basedir}/src/main/avro</sourceDirectory>
        <outputDirectory>${project.build.directory}/generated-sources/avro</outputDirectory>
        <stringType>String</stringType>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### Docker Compose — Kafka KRaft Mode

Kafka runs in KRaft mode (no Zookeeper). Key environment variables:
```yaml
KAFKA_NODE_ID: 1
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093,PLAINTEXT_HOST://0.0.0.0:29092
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
CLUSTER_ID: 'MkU3OEVBNTcwNTJENDM2Qk' # Generate with kafka-storage random-uuid
```

### Frontend — create-vue Scaffold

Run from `frontend/` directory:
```bash
npm create vue@latest customer-website -- --typescript --router --pinia --vitest --eslint-with-prettier
npm create vue@latest admin-dashboard -- --typescript --router --pinia --vitest --eslint-with-prettier
```

The `@robo-mart/shared` package is initially empty — just package.json with name and version. Design tokens, themes, and components are added in Story 1.6.

### Project Structure Notes

```
robo-mart/
├── backend/
│   ├── pom.xml                       # Parent POM (spring-boot-starter-parent:4.0.4)
│   ├── .mvn/                         # Maven Wrapper
│   ├── mvnw, mvnw.cmd
│   ├── config/checkstyle/
│   │   └── checkstyle.xml
│   ├── common-lib/
│   │   ├── pom.xml
│   │   └── src/main/java/com/robomart/common/
│   │       ├── config/               # JacksonConfig, LoggingConfig
│   │       ├── dto/                  # ApiResponse, ApiErrorResponse, PagedResponse, PaginationMeta
│   │       ├── entity/               # BaseEntity
│   │       ├── exception/            # RoboMartException hierarchy + GlobalExceptionHandler
│   │       ├── filter/               # CorrelationIdFilter
│   │       └── logging/              # ErrorCode enum
│   ├── security-lib/                 # Empty placeholder
│   │   └── pom.xml
│   ├── proto/                        # Empty placeholder
│   │   └── pom.xml
│   ├── events/
│   │   ├── pom.xml
│   │   └── src/main/avro/
│   │       └── base_event_envelope.avsc
│   ├── test-support/                 # Empty placeholder
│   │   └── pom.xml
│   └── product-service/
│       ├── pom.xml
│       └── src/main/java/com/robomart/product/
│           ├── ProductServiceApplication.java
│           ├── config/
│           ├── controller/
│           ├── dto/
│           ├── entity/
│           ├── exception/
│           ├── mapper/
│           ├── repository/
│           ├── service/
│           └── event/
├── frontend/
│   ├── package.json                  # npm workspaces root
│   ├── shared/                       # @robo-mart/shared
│   │   ├── package.json
│   │   └── src/
│   │       ├── tokens/
│   │       ├── themes/
│   │       └── components/
│   ├── customer-website/             # Vue.js SPA (create-vue scaffold)
│   │   └── package.json
│   └── admin-dashboard/              # Vue.js SPA (create-vue scaffold)
│       └── package.json
├── infra/
│   ├── docker/
│   │   ├── docker-compose.yml
│   │   └── .env
│   ├── k8s/                          # Empty — populated later
│   └── ci/                           # Empty — populated in Epic 10
├── docs/
├── .editorconfig
└── .gitignore
```

### References

- [Source: _bmad-output/planning-artifacts/architecture.md#Starter Template Evaluation] — Project structure, Maven multi-module, technology versions
- [Source: _bmad-output/planning-artifacts/architecture.md#Core Architectural Decisions] — Data architecture, communication patterns
- [Source: _bmad-output/planning-artifacts/architecture.md#Implementation Patterns] — Exception hierarchy, logging format, API response wrapper
- [Source: _bmad-output/planning-artifacts/architecture.md#Infrastructure & Deployment] — Docker Compose profiles, container list
- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.1] — Acceptance criteria, user story

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
