# Codex Guide — Robo-Mart

## Working agreements

- Communicate with the user in Vietnamese. Write new technical documentation in English unless the user requests another language; preserve the language of existing documents.
- Inspect the relevant implementation, configuration, and tests before editing. Prefer a focused change that follows nearby code conventions.
- Preserve unrelated local changes. Never commit secrets or local `.env` files.
- Treat current source, `pom.xml`, `package.json`, Compose files, and CI workflows as evidence of actual behavior. `CLAUDE.md` and BMAD artifacts provide useful context but can describe older or planned behavior.
- Run checks appropriate to the change. Report commands run, results, and checks blocked by missing dependencies; never describe an unexecuted build or test as passing.

## Repository map and context

- `backend/`: Java 21, Spring Boot 4.0.4, Spring Cloud 2025.1.1; Maven multimodule project.
- `frontend/`: npm workspaces containing Vue 3 / TypeScript apps `customer-website` and `admin-dashboard`, plus `@robo-mart/shared`. UI uses Pinia, PrimeVue, Tailwind, and Vite.
- `infra/docker/`: local dependencies and optional application containers. `infra/k8s/`: Kubernetes and GitOps configuration.
- `.github/workflows/ci-backend.yml` and `ci-frontend.yml`: executable CI reference.
- `docs/development-system-flow.md`: development and business-flow overview, including known implementation/configuration differences.
- `_bmad-output/planning-artifacts/{prd,architecture,epics}.md`: requirements and design context.
- `_bmad-output/implementation-artifacts/`: story records, investigations, retrospectives, `sprint-status.yaml`, and `deferred-work.md`. Read relevant records before changing the associated feature; a story marked done is not proof that runtime behavior works.

## BMAD usage from Codex

BMAD core/BMM 6.8.0 and BMB, CIS, TEA, WDS modules are installed under `_bmad/`. The installation manifest currently lists `claude-code`; workflow entry points live in `.claude/skills/`. These are repository files to read explicitly, not assumed Codex slash commands or automatically installed Codex skills.

- For ordinary fixes and maintenance, work directly within the requested scope. Do not require a BMAD ceremony for every change.
- When the user requests a BMAD workflow, locate its entry in `_bmad/_config/bmad-help.csv` or `_bmad/_config/skill-manifest.csv`, then read the corresponding `.claude/skills/<name>/SKILL.md` and referenced files. Resolve paths relative to that skill directory and `{project-root}` to the repository root.
- Load the applicable module config, especially `_bmad/bmm/config.yaml`, `_bmad/core/config.yaml`, and `_bmad/tea/config.yaml` for testing. Respect the workflow's customization resolver and team/user overrides; do not edit generated workflow defaults to customize behavior.
- Useful entry points: `bmad-create-story`, `bmad-dev-story`, `bmad-code-review`, `bmad-quick-dev`, `bmad-investigate`, and `bmad-testarch-test-design` under `.claude/skills/`.
- For story implementation, read the entire selected story, its acceptance criteria, the sprint status, and relevant architecture/deferred-work records. Update story tracking only for work actually completed. Follow the workflow's permitted story sections and status transitions; implementation ready for review is not automatically `done`.
- Keep planning outputs in `_bmad-output/planning-artifacts/`, implementation outputs in `_bmad-output/implementation-artifacts/`, test outputs in `_bmad-output/test-artifacts/`, and project knowledge in `docs/` as configured.
- Read only the workflow and context needed for the task; do not activate agents or execute workflow steps merely while inspecting their documentation.

## Prerequisites and local infrastructure

Use JDK 21 and `backend/mvnw`. Use npm with `frontend/package-lock.json`; CI uses Node 22, and app engines allow `^20.19.0 || >=22.12.0`. Docker with Compose v2 is needed for local infrastructure and Testcontainers integration tests.

Run these commands from the **repository root**:

```bash
# Create local configuration only when absent; review values before starting.
test -f infra/docker/.env || cp infra/docker/.env.example infra/docker/.env
docker compose -f infra/docker/docker-compose.yml --profile core up -d
docker compose -f infra/docker/docker-compose.yml --profile core ps
```

The `core` profile starts dependencies, not backend applications. For application containers as well:

```bash
docker compose -f infra/docker/docker-compose.yml --profile core --profile app up -d --build
```

Check `docs/development-system-flow.md` and Compose configuration for host/container addressing and full-stack limitations. Never remove Docker volumes as routine troubleshooting.

## Backend commands

Run all commands in this section from **`backend/`**.

```bash
# Compile/package/install every module without executing tests (CI build step).
./mvnw install -T 1C -DskipTests -DskipITs -DskipE2ETests

# Build one service and its reactor dependencies before testing/running it.
./mvnw install -pl :product-service -am -DskipTests -DskipITs -DskipE2ETests

# Start a service on the host after installing dependencies and starting infra.
./mvnw spring-boot:run -pl :product-service

# Unit tests for that service; reactor dependencies must already be installed.
./mvnw test -pl :product-service
./mvnw test -pl :product-service -Dtest=ProductServiceCacheTest
./mvnw test -pl :product-service '-Dtest=ProductServiceCacheTest#methodName'

# Unit + integration tests and coverage gate for the selected module (Docker needed).
./mvnw verify -pl :product-service -DskipE2ETests

# CI's unit-only module verification (after the initial install).
./mvnw verify -pl :common-lib,:security-lib,:proto,:api-gateway -DskipITs -DskipE2ETests

# Explicit style check; rules live in config/checkstyle/checkstyle.xml.
./mvnw checkstyle:check

# Full reactor verification including integration tests; E2E remains skipped.
./mvnw verify -DskipE2ETests
```

- Replace `product-service` with the affected module. Other runnable modules: `cart-service`, `inventory-service`, `payment-service`, `order-service`, `notification-service`, `api-gateway`. Run each service in its own terminal.
- Use `-am` for dependency installation, then test/verify only the target module to avoid rerunning upstream integration tests. With `-Dtest`, avoid adding `-am` unless you account for modules without that test.
- Maven `test` runs Surefire unit tests; `verify` also runs configured Failsafe integration tests and JaCoCo checks. Testcontainers support is in `test-support/`. Inspect module POMs and test requirements for exceptions.
- `-DskipTests` also skips Failsafe tests. Do not use it when claiming to validate integration tests. Do not disable coverage or quality gates to hide failures. The inherited JaCoCo gate is 80% line coverage, with configured exclusions.
- End-to-end tests are opt-in. With a prepared full stack and test data, use `./mvnw verify -pl :e2e-tests -DskipE2ETests=false`. Inspect `backend/e2e-tests/src/test/` first: the module also contains chaos tests and requires appropriate service access.
- Performance tests require k6, prepared data, and a running stack; follow `backend/k6/README.md` rather than including them in routine checks.

## Frontend commands

Run all commands in this section from **`frontend/`**. The workspace root has no `dev`, `build`, `lint`, `format`, `type-check`, or `test:unit` scripts; target an app explicitly.

```bash
# Install the committed dependency graph for all workspaces.
npm ci

# Run in separate terminals; default ports are customer 5173, admin 5174.
npm run dev --workspace=customer-website
npm run dev --workspace=admin-dashboard

# Validate one app; replace the workspace name for admin-dashboard.
npm run type-check --workspace=customer-website
npm run test:unit --workspace=customer-website -- --run
npm run build --workspace=customer-website

# Validate both apps (shared has no corresponding scripts).
npm run type-check --workspaces --if-present
npm run test:unit --workspaces --if-present -- --run
npm run build --workspaces --if-present

# Existing lint and format scripts MODIFY files; review resulting diffs.
npm run lint --workspace=customer-website
npm run format --workspace=customer-website

# Read-only formatting checks, matching CI.
npx prettier --check customer-website/src/
npx prettier --check admin-dashboard/src/
```

`test:unit` launches Vitest; pass `--run` for noninteractive verification. `build` includes type checking; `build-only` only bundles with Vite. Changes to `frontend/shared/` should be checked against both consuming apps as appropriate. Keep the npm lockfile consistent when changing dependencies.

Optional Git hook setup, from the **repository root**: `bash frontend/setup-hooks.sh`. Inspect existing hooks before installation: this script replaces `.git/hooks/pre-push` with a symlink. The pre-push script uses lint-staged and can modify/stage files; it is not a replacement for running tests.

## Implementation conventions

- Preserve service ownership of data. Add schema changes through new Flyway migrations in the owning service's `src/main/resources/db/migration/`; do not rewrite applied migrations.
- Shared modules: `common-lib` (DTOs, exceptions, common configuration), `security-lib` (JWT/auth), `proto` (gRPC contracts), `events` (Avro schemas), `test-support` (test fixtures). Edit contract sources rather than generated Java files.
- Preserve outbox/event delivery semantics, payment idempotency, inventory locking, and Saga compensation. Add regression coverage for affected failure paths when changing checkout, cancellation, retry, or refund behavior.
- Synchronous checkout orchestration uses gRPC from Order to Inventory/Payment. Kafka/Avro handles asynchronous events; do not assume it orchestrates checkout.
- Spring Boot 4 uses Jackson 3: databind imports are `tools.jackson.databind`; annotations remain `com.fasterxml.jackson.annotation`. Follow existing `JsonMapperBuilderCustomizer` usage rather than introducing Jackson 2 configuration.
- Follow existing Spring Boot 4 / Framework 7 dependencies and test patterns. Prefer `@MockitoBean` over `@MockBean`; inspect neighboring tests before copying older Spring examples. Gateway uses `spring-cloud-starter-gateway-server-webflux`.
- Keycloak realm is `robomart`. Roles come from nested `realm_access.roles`, mapped by `KeycloakRealmRoleConverter` in `security-lib`. Preserve JWT validation and RBAC boundaries.
- Testcontainers 2.x artifacts use names such as `testcontainers-postgresql` and `testcontainers-junit-jupiter`; reuse the configured versions and shared fixtures.
- Reuse existing Vue components, Pinia stores, shared design tokens, and OIDC integration. Validate type safety and accessibility for UI changes.

## Local ports

| Component | Host port |
| --- | --- |
| API Gateway | 8080 |
| Product / Cart / Order / Inventory | 8081 / 8082 / 8083 / 8084 |
| Payment / Notification | 8086 / 8087 |
| Customer / Admin | 5173 / 5174 |
| Keycloak | 8180 |
| PostgreSQL: product / keycloak / order / inventory / payment / notification | 5432 / 5433 / 5434 / 5435 / 5436 / 5437 |
| Redis / Elasticsearch | 6379 / 9200 |
| Kafka (host listener) / Schema Registry / Kafka UI | 29092 / 8085 / 9090 |

These are local defaults, not a guarantee that every app-profile container publishes the same ports. Kafka inside Docker uses `kafka:9092`; host processes use `localhost:29092`. Check service configuration before changing networking or CORS.
