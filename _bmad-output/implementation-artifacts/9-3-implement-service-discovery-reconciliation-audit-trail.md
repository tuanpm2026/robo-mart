# Story 9.3: Implement Service Discovery, Reconciliation & Audit Trail

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a system operator,
I want dynamic service discovery, data reconciliation, and a complete audit trail,
So that the system self-manages, detects inconsistencies, and provides accountability.

## Acceptance Criteria

1. **Given** K8s service discovery
   **When** services communicate
   **Then** they discover instances dynamically via K8s DNS (service-name.namespace.svc.cluster.local) — no hardcoded addresses (FR69)

2. **Given** scheduled reconciliation jobs
   **When** running daily
   **Then** they compare: inventory count vs order records, payment records vs order status — and alert admin when variance exceeds threshold (>1% discrepancy or >5 unit absolute difference) (FR71)

3. **Given** reconciliation results
   **When** discrepancy is found
   **Then** an alert is generated for admin with: affected entities, expected vs actual values, suggested resolution

4. **Given** any state-changing operation across all services
   **When** executed
   **Then** an audit trail record is created with: actor (user ID or system), action (CREATE/UPDATE/DELETE), entity type, entity ID, timestamp, trace ID (FR72, NFR18)

5. **Given** audit trail records
   **When** queried
   **Then** they are searchable by actor, action, entity, time range, and trace ID

## Tasks / Subtasks

### Part A: Service Discovery Verification (AC1)

#### Task 1: Verify & Complete K8s DNS Configuration (AC1)

- [ ] **Verify** `infra/k8s/base/configmap.yml` already provides K8s DNS overrides for all service-to-service communication (done in Story 9.2 — see Dev Notes below for full mapping)
- [ ] **Verify** api-gateway `application.yml` `gateway.services.*` defaults → override via `GATEWAY_SERVICES_*` env vars (Spring relaxed binding: `GATEWAY_SERVICES_PRODUCT_SERVICE` → `gateway.services.product-service`)
- [ ] **Verify** order-service `application.yml` gRPC clients use `${GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS}` / `${GRPC_CLIENT_PAYMENT_SERVICE_ADDRESS}` — already done (9.2 patch D2)
- [ ] **Verify** notification-service `application.yml` maps `notification.*.url` to `${ORDER_SERVICE_URL}`, `${PRODUCT_SERVICE_URL}`, etc. — already correct
- [ ] **No code changes needed** if verification passes — document in Dev Notes that AC1 is satisfied by Story 9.2 ConfigMap + env var interpolation pattern across all services

---

### Part B: Reconciliation Jobs (AC2, AC3)

> **Architecture decision**: Reconciliation runs in `notification-service` (the admin operations hub) — it already holds `RestClient` instances for all services and has `AdminPushService` for WebSocket alerts. Order-service stays focused on order processing.

#### Task 2: Add Reconciliation Summary Endpoints to inventory-service (AC2)

- [ ] **File**: `backend/inventory-service/src/main/java/com/robomart/inventory/controller/InventoryAdminRestController.java` — add a new endpoint (or create the class if not yet present)
- [ ] Check if `InventoryAdminRestController` already exists; if not, create it with `@RestController`, `@RequestMapping("/api/v1/admin/inventory")`, `@PreAuthorize("hasRole('ADMIN')")`
- [ ] Add endpoint:
  ```java
  @GetMapping("/reconciliation-summary")
  public ReconciliationSummaryResponse getReconciliationSummary() { ... }
  ```
- [ ] **New DTO**: `backend/inventory-service/src/main/java/com/robomart/inventory/dto/ReconciliationSummaryResponse.java`
  ```java
  public record ReconciliationSummaryResponse(
      List<ProductInventorySummary> items,
      Instant generatedAt
  ) {}
  public record ProductInventorySummary(
      Long productId,
      int availableQuantity,
      int reservedQuantity,
      int totalQuantity
  ) {}
  ```
- [ ] **Service method**: `InventoryService.getReconciliationSummary()` — query `inventory_items` table for all rows, return summary
- [ ] The endpoint returns `ApiResponse<ReconciliationSummaryResponse>` (common-lib pattern)

#### Task 3: Add Reconciliation Summary Endpoints to payment-service (AC2)

- [ ] **File**: `backend/payment-service/src/main/java/com/robomart/payment/controller/PaymentAdminRestController.java` — add or create
- [ ] Check if `PaymentAdminRestController` already exists; if not, create with same pattern as above
- [ ] Add endpoint:
  ```java
  @GetMapping("/reconciliation-summary")
  public ReconciliationSummaryResponse getReconciliationSummary() { ... }
  ```
- [ ] **New DTO**: `backend/payment-service/src/main/java/com/robomart/payment/dto/ReconciliationSummaryResponse.java`
  ```java
  public record ReconciliationSummaryResponse(
      List<OrderPaymentSummary> payments,
      Instant generatedAt
  ) {}
  public record OrderPaymentSummary(
      String orderId,
      String status,    // PENDING, COMPLETED, FAILED, REFUNDED
      Long amount
  ) {}
  ```
- [ ] **Service method**: `PaymentService.getReconciliationSummary()` — query `payments` table (existing entity), return all payment records
- [ ] The endpoint returns `ApiResponse<ReconciliationSummaryResponse>`

#### Task 4: Add Reconciliation Summary Endpoint to order-service (AC2)

- [ ] **File**: Check if `OrderAdminRestController` exists or add to `OrderRestController`
- [ ] Add endpoint: `GET /api/v1/admin/orders/reconciliation-summary`
  ```java
  public record OrderReconciliationSummary(
      String orderId,
      String status,       // ORDER status enum
      List<OrderItemSummary> items   // productId + quantity
  ) {}
  ```
- [ ] **Service method**: query `orders` + `order_items` tables for all active (non-CANCELLED) orders with their items

#### Task 5: Implement ReconciliationService in notification-service (AC2, AC3)

- [ ] **File**: `backend/notification-service/src/main/java/com/robomart/notification/service/ReconciliationService.java`
- [ ] `@Service` with injected `RestClient` instances for inventory-service, payment-service, order-service (reuse existing pattern from `HealthAggregatorService` — use separate `RestClient` per service with `@PostConstruct` init)
- [ ] **Configuration properties** (add to `notification-service/application.yml`):
  ```yaml
  notification:
    reconciliation:
      inventory-threshold-percent: 1.0    # >1% variance triggers alert
      inventory-threshold-absolute: 5     # OR >5 units absolute variance
      payment-threshold-percent: 1.0
  ```
- [ ] `runInventoryReconciliation()`:
  1. Call `GET {inventoryUrl}/api/v1/admin/inventory/reconciliation-summary` → inventory reserved quantities per product
  2. Call `GET {orderUrl}/api/v1/admin/orders/reconciliation-summary` → active orders and their quantities
  3. For each product: sum order quantities in INVENTORY_RESERVING/PAYMENT_PROCESSING/CONFIRMED states → expected reserved qty
  4. Compare with inventory `reservedQuantity`
  5. If `|actual - expected| > 5` OR `|actual - expected| / expected > 0.01` → discrepancy
  6. Return `ReconciliationResult` with list of discrepancies
- [ ] `runPaymentReconciliation()`:
  1. Call `GET {paymentUrl}/api/v1/admin/payments/reconciliation-summary` → all payment records by orderId
  2. Call `GET {orderUrl}/api/v1/admin/orders/reconciliation-summary` → all active orders with status
  3. For each CONFIRMED order: expect a COMPLETED payment record
  4. For each CANCELLED order: expect no PENDING payment (or a REFUNDED one)
  5. Return `ReconciliationResult` with list of discrepancies
- [ ] **Alert on discrepancy**: call `adminPushService.pushReconciliationAlert(discrepancies)` (see Task 6) AND log at WARN level with structured fields: `reconciliationType`, `discrepancyCount`, `affectedEntities`
- [ ] **New DTOs** in `notification-service/service/dto/`:
  ```java
  public record ReconciliationDiscrepancy(
      String entityType,      // "INVENTORY" or "PAYMENT"
      String entityId,        // productId or orderId
      String expected,        // expected value as string
      String actual,          // actual value as string
      String suggestedResolution
  ) {}
  public record ReconciliationResult(
      String type,
      List<ReconciliationDiscrepancy> discrepancies,
      boolean hasDiscrepancies,
      Instant checkedAt
  ) {}
  ```
- [ ] When RestClient call fails (service unavailable), log WARN and skip that reconciliation — do NOT fail the scheduler

#### Task 6: Add Reconciliation Alert Support to AdminPushService (AC3)

- [ ] **File**: `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java` — add method:
  ```java
  public void pushReconciliationAlert(ReconciliationResult result) {
      // Check existing push method pattern and replicate
      // Push via WebSocket STOMP to /topic/admin/reconciliation-alerts
  }
  ```
- [ ] Check existing `AdminPushService` for the WebSocket topic and message format — replicate exactly

#### Task 7: Implement ReconciliationScheduler in notification-service (AC2)

- [ ] **File**: `backend/notification-service/src/main/java/com/robomart/notification/service/ReconciliationScheduler.java`
- [ ] `@Component` with `@Scheduled` jobs:
  ```java
  @Scheduled(cron = "${notification.reconciliation.cron:0 0 2 * * *}")  // 2 AM daily by default
  public void runDailyInventoryReconciliation() { ... }

  @Scheduled(cron = "${notification.reconciliation.payment-cron:0 30 2 * * *}")  // 2:30 AM daily
  public void runDailyPaymentReconciliation() { ... }
  ```
- [ ] Each method: delegate to `reconciliationService.run*()`, log result at INFO level
- [ ] **notification-service already enables scheduling** — check `SchedulingConfig.java` in `config/` package. Use existing `@EnableScheduling` config if present.
- [ ] Add to `application.yml` notification section:
  ```yaml
  notification:
    reconciliation:
      cron: "0 0 2 * * *"
      payment-cron: "0 30 2 * * *"
      inventory-threshold-percent: 1.0
      inventory-threshold-absolute: 5
      payment-threshold-percent: 1.0
  ```

#### Task 8: Add Reconciliation Query Endpoint to notification-service (AC3)

- [ ] **File**: `backend/notification-service/src/main/java/com/robomart/notification/controller/ReconciliationAdminRestController.java`
- [ ] `@RestController`, `@RequestMapping("/api/v1/admin/reconciliation")`, `@PreAuthorize("hasRole('ADMIN')")`
- [ ] `POST /api/v1/admin/reconciliation/run` — triggers immediate reconciliation run (for testing/manual trigger)
- [ ] `GET /api/v1/admin/reconciliation/status` — returns last reconciliation results (store in memory or cache in `ReconciliationService` as `volatile`)

---

### Part C: Audit Trail — common-lib (AC4)

#### Task 9: Add @Auditable Annotation and AuditAspect to common-lib (AC4)

- [ ] **File**: `backend/common-lib/src/main/java/com/robomart/common/audit/Auditable.java`
  ```java
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  public @interface Auditable {
      AuditAction action();     // CREATE, UPDATE, DELETE
      String entityType();      // e.g. "ORDER", "PRODUCT", "PAYMENT"
  }
  ```
- [ ] **File**: `backend/common-lib/src/main/java/com/robomart/common/audit/AuditAction.java`
  ```java
  public enum AuditAction { CREATE, UPDATE, DELETE }
  ```
- [ ] **File**: `backend/common-lib/src/main/java/com/robomart/common/audit/AuditEvent.java`
  ```java
  public record AuditEvent(
      String actor,        // user ID from SecurityContext, or "SYSTEM" for scheduled jobs
      AuditAction action,
      String entityType,
      String entityId,     // String representation of the entity ID
      Instant timestamp,
      String traceId,      // from MDC "traceId"
      String correlationId // from MDC "correlationId"
  ) {}
  ```
- [ ] **File**: `backend/common-lib/src/main/java/com/robomart/common/audit/AuditEventListener.java` — interface for services to implement:
  ```java
  public interface AuditEventListener {
      void onAuditEvent(AuditEvent event);
  }
  ```
- [ ] **File**: `backend/common-lib/src/main/java/com/robomart/common/audit/AuditAspect.java`
  - `@Aspect`, `@Component`
  - Depends on Spring Security's `SecurityContextHolder` (already on classpath in all services)
  - Depends on SLF4J MDC for traceId/correlationId
  - Constructor-injected: `List<AuditEventListener> listeners` (Spring collects all beans implementing the interface)
  - `@Around("@annotation(auditable)")` advice:
    1. Extract actor: `SecurityContextHolder.getContext().getAuthentication()?.name ?: "SYSTEM"`
    2. Get traceId: `MDC.get("traceId")`, correlationId: `MDC.get("correlationId")`
    3. Proceed with method execution — get return value
    4. Extract entityId from return value: if return type implements `EntityIdProvider` → call `getEntityId()`, else use `String.valueOf(returnValue)` truncated to 255 chars, else `"UNKNOWN"`
    5. Build `AuditEvent`, notify all `AuditEventListener` beans
    6. Do NOT catch exceptions — let them propagate (failed operations should not be audited)
  - **IMPORTANT**: Do NOT make `AuditAspect` conditional — it's always active. If no `AuditEventListener` beans exist, it silently no-ops (empty list → no-op loop)
  - **IMPORTANT**: Add `@Order(Ordered.LOWEST_PRECEDENCE)` — audit runs AFTER transaction commits (after `@Transactional` advice completes). Use Spring AOP proxy ordering, NOT `@AfterReturning` on `@Transactional` methods. For safety, use `@Around` and call `proceed()` first, then audit.
- [ ] **File**: `backend/common-lib/src/main/java/com/robomart/common/audit/EntityIdProvider.java`
  ```java
  public interface EntityIdProvider {
      String getEntityId();
  }
  ```
- [ ] **pom.xml** (common-lib): Ensure `spring-boot-starter-aop` dependency is present (needed for `@Aspect`). Check first — it may already be there via existing dependencies.
- [ ] **`spring.factories`** or `AutoConfiguration.imports` — common-lib already uses Spring Boot autoconfiguration. Add `AuditAspect` to the scan. Since services import common-lib and component-scan their own packages, ensure common-lib's `com.robomart.common.audit` package is included. Add `@ComponentScan` entry in existing common-lib config OR add `@AutoConfiguration` + META-INF registration.
  - **Simplest approach**: Add `com.robomart.common.audit` to the existing `LoggingConfig.java` or `TracingConfig.java` package scanning. Actually, Spring Boot's `@SpringBootApplication` on each service scans its own package ONLY. For common-lib classes to be auto-discovered, they need to be in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  - Check `backend/common-lib/src/main/resources/META-INF/spring/` — if this file exists, add the AuditAspect config class. If not, create it.

#### Task 10: Add Audit Log Table to Each Service (AC4)

For each of the 4 services with PostgreSQL DB (**order-service, inventory-service, payment-service, product-service**):

- [ ] **order-service**: `backend/order-service/src/main/resources/db/migration/V4__create_audit_log_table.sql`
- [ ] **inventory-service**: `backend/inventory-service/src/main/resources/db/migration/V2__create_audit_log_table.sql`
- [ ] **payment-service**: `backend/payment-service/src/main/resources/db/migration/V2__create_audit_log_table.sql`
- [ ] **product-service**: `backend/product-service/src/main/resources/db/migration/V6__create_audit_log_table.sql`

Each SQL file:
```sql
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    actor           VARCHAR(255) NOT NULL,
    action          VARCHAR(20)  NOT NULL,   -- CREATE, UPDATE, DELETE
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       VARCHAR(255) NOT NULL,
    trace_id        VARCHAR(255),
    correlation_id  VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_actor       ON audit_log(actor);
CREATE INDEX idx_audit_log_entity_type ON audit_log(entity_type);
CREATE INDEX idx_audit_log_entity_id   ON audit_log(entity_id);
CREATE INDEX idx_audit_log_trace_id    ON audit_log(trace_id);
CREATE INDEX idx_audit_log_created_at  ON audit_log(created_at);
```

#### Task 11: Add AuditLog Entity and Repository to Each Service (AC4)

For each of the 4 services (order, inventory, payment, product):

- [ ] **Entity**: `AuditLog.java` in `entity/` package
  ```java
  @Entity @Table(name = "audit_log")
  public class AuditLog {
      @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;
      @Column(nullable = false, length = 255) private String actor;
      @Column(nullable = false, length = 20)  private String action;
      @Column(nullable = false, length = 100) private String entityType;
      @Column(nullable = false, length = 255) private String entityId;
      @Column(length = 255) private String traceId;
      @Column(length = 255) private String correlationId;
      @Column(nullable = false)               private Instant createdAt;
      // Standard getters/setters (no Lombok — project style)
  }
  ```
- [ ] **Repository**: `AuditLogRepository.java` in `repository/` package
  ```java
  public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
      Page<AuditLog> findByActorContainingIgnoreCase(String actor, Pageable pageable);
      Page<AuditLog> findByEntityTypeAndEntityId(String entityType, String entityId, Pageable pageable);
      Page<AuditLog> findByTraceId(String traceId, Pageable pageable);
      Page<AuditLog> findByCreatedAtBetween(Instant from, Instant to, Pageable pageable);
      @Query("SELECT a FROM AuditLog a WHERE " +
             "(:actor IS NULL OR LOWER(a.actor) LIKE LOWER(CONCAT('%', :actor, '%'))) AND " +
             "(:action IS NULL OR a.action = :action) AND " +
             "(:entityType IS NULL OR a.entityType = :entityType) AND " +
             "(:traceId IS NULL OR a.traceId = :traceId) AND " +
             "(:from IS NULL OR a.createdAt >= :from) AND " +
             "(:to IS NULL OR a.createdAt <= :to)")
      Page<AuditLog> search(@Param("actor") String actor,
                            @Param("action") String action,
                            @Param("entityType") String entityType,
                            @Param("traceId") String traceId,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);
  }
  ```

#### Task 12: Add AuditEventListener Implementation to Each Service (AC4)

For each of the 4 services:

- [ ] **File**: `AuditLogService.java` in `service/` package — implements `AuditEventListener`:
  ```java
  @Service
  public class AuditLogService implements AuditEventListener {
      private final AuditLogRepository auditLogRepository;
      // constructor injection
      @Override
      @Transactional(propagation = Propagation.REQUIRES_NEW)
          // REQUIRES_NEW: audit log persists even if caller transaction rolls back? 
          // Actually: audit ONLY on success. Aspect calls this AFTER proceed() — 
          // if proceed() threw, we skip. So REQUIRED is fine.
      public void onAuditEvent(AuditEvent event) {
          AuditLog log = new AuditLog();
          log.setActor(event.actor());
          log.setAction(event.action().name());
          log.setEntityType(event.entityType());
          log.setEntityId(event.entityId());
          log.setTraceId(event.traceId());
          log.setCorrelationId(event.correlationId());
          log.setCreatedAt(event.timestamp());
          auditLogRepository.save(log);
      }
  }
  ```
- [ ] **NOTE**: Use `@Transactional(propagation = Propagation.REQUIRES_NEW)` so audit log saves in its own transaction, preventing audit failure from rolling back the main business transaction.

#### Task 13: Add @Auditable Annotations to Key Service Methods (AC4)

State-changing methods to annotate:

**order-service** — `OrderSagaOrchestrator.java` or `OrderService.java`:
- [ ] `createOrder(...)` → `@Auditable(action = AuditAction.CREATE, entityType = "ORDER")`
- [ ] `cancelOrder(...)` → `@Auditable(action = AuditAction.DELETE, entityType = "ORDER")`
- [ ] Return value must provide entityId — if returns `Order`, implement `EntityIdProvider` on Order entity OR extract from the DTO response. Check how order ID is returned.

**inventory-service** — `InventoryService.java`:
- [ ] `adjustStock(...)` → `@Auditable(action = AuditAction.UPDATE, entityType = "INVENTORY")`
- [ ] `StockReservationService.reserve(...)` → `@Auditable(action = AuditAction.UPDATE, entityType = "INVENTORY")`

**payment-service** — `PaymentService.java`:
- [ ] `processPayment(...)` → `@Auditable(action = AuditAction.CREATE, entityType = "PAYMENT")`
- [ ] `processRefund(...)` → `@Auditable(action = AuditAction.UPDATE, entityType = "PAYMENT")`

**product-service** — `ProductService.java`:
- [ ] `createProduct(...)` → `@Auditable(action = AuditAction.CREATE, entityType = "PRODUCT")`
- [ ] `updateProduct(...)` → `@Auditable(action = AuditAction.UPDATE, entityType = "PRODUCT")`
- [ ] `deleteProduct(...)` → `@Auditable(action = AuditAction.DELETE, entityType = "PRODUCT")`

**IMPORTANT**: Before annotating, **read each service class first** to understand return types and method signatures. The aspect extracts entityId from the return value. If the return type doesn't implement `EntityIdProvider`, the aspect falls back to `String.valueOf(returnValue)` — ensure that produces a meaningful ID.

#### Task 14: Add Admin Query Endpoints to Each Service (AC5)

For each of the 4 services, add to existing admin REST controller (or create if absent):

- [ ] `GET /api/v1/admin/audit-logs` with query params: `actor`, `action`, `entityType`, `entityId`, `traceId`, `from` (ISO-8601), `to` (ISO-8601), `page` (0-based), `size` (default 20)
- [ ] Returns `ApiResponse<Page<AuditLogDto>>` — use common-lib `PagedResponse` pattern
- [ ] **DTO**: `AuditLogDto` (record in `dto/` package):
  ```java
  public record AuditLogDto(Long id, String actor, String action, String entityType,
                            String entityId, String traceId, String correlationId, Instant createdAt) {}
  ```
- [ ] Delegate to `AuditLogService.search(actor, action, entityType, traceId, from, to, pageable)`
- [ ] Access: `@PreAuthorize("hasRole('ADMIN')")`

#### Task 15: Add Aggregated Audit Query to notification-service (AC5)

- [ ] **File**: `backend/notification-service/src/main/java/com/robomart/notification/service/AuditAggregatorService.java`
  - Injects `RestClient` instances for all 4 services (order, inventory, payment, product)
  - `searchAuditLogs(String actor, String action, String entityType, String traceId, Instant from, Instant to, int page, int size)`:
    - Fan out to all 4 services in parallel (use `CompletableFuture` or sequential for simplicity)
    - Merge results, sort by `createdAt` descending
    - Apply pagination on merged results
- [ ] **File**: `backend/notification-service/src/main/java/com/robomart/notification/controller/AuditAdminRestController.java`
  - `@RestController`, `@RequestMapping("/api/v1/admin/audit-logs")`, `@PreAuthorize("hasRole('ADMIN')")`
  - `GET /` — aggregated search across all services
  - Returns merged `List<AuditLogDto>` with total count

---

### Part D: Tests and Verification (AC1–AC5)

#### Task 16: Unit Tests for AuditAspect in common-lib (AC4)

- [ ] **File**: `backend/common-lib/src/test/java/com/robomart/common/audit/AuditAspectTest.java`
- [ ] Test method: `shouldCreateAuditEventWhenAuditableMethodSucceeds()`
- [ ] Test method: `shouldNotCreateAuditEventWhenMethodThrowsException()`
- [ ] Test method: `shouldUseSystemActorWhenNoSecurityContext()`
- [ ] Use `@SpringBootTest(classes = {AuditAspect.class, TestAuditTarget.class})` or plain Mockito
- [ ] Mock `AuditEventListener` and verify `onAuditEvent()` called with correct fields

#### Task 17: Unit Tests for ReconciliationService in notification-service (AC2, AC3)

- [ ] **File**: `backend/notification-service/src/test/java/com/robomart/notification/unit/service/ReconciliationServiceTest.java`
- [ ] Test: `shouldDetectInventoryDiscrepancyAboveAbsoluteThreshold()`
- [ ] Test: `shouldDetectInventoryDiscrepancyAbovePercentThreshold()`
- [ ] Test: `shouldNotAlertWhenInventoryIsConsistent()`
- [ ] Test: `shouldDetectPaymentMissingForConfirmedOrder()`
- [ ] Mock the `RestClient` calls for inventory-service, payment-service, order-service summaries

#### Task 18: Compile and Regression Test (AC1–AC5)

- [ ] `cd backend && ./mvnw clean compile -T 1C` — ensure all services compile
- [ ] `cd backend && ./mvnw test -pl :common-lib` — verify AuditAspect unit tests pass
- [ ] `cd backend && ./mvnw test -pl :order-service` — verify no regressions (90/90 target)
- [ ] `cd backend && ./mvnw test -pl :product-service` — verify no regressions (63/63 target)
- [ ] `cd backend && ./mvnw test -pl :notification-service` — verify no regressions (37/37 target)
- [ ] `cd backend && ./mvnw checkstyle:check` — no checkstyle violations

---

## Dev Notes

### AC1 — Service Discovery: Already Satisfied by Story 9.2

All inter-service communication already uses env var interpolation + K8s ConfigMap overrides (no hardcoded non-localhost addresses):

| Service | URL Pattern | ConfigMap Key |
|---------|------------|---------------|
| api-gateway → all services | `gateway.services.*` defaults, overridden by `GATEWAY_SERVICES_*` | `GATEWAY_SERVICES_PRODUCT_SERVICE`, etc. |
| order-service → inventory (gRPC) | `${GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS:static://localhost:9094}` | `GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS: "static://inventory-service:9094"` |
| order-service → payment (gRPC) | `${GRPC_CLIENT_PAYMENT_SERVICE_ADDRESS:static://localhost:9095}` | `GRPC_CLIENT_PAYMENT_SERVICE_ADDRESS: "static://payment-service:9095"` |
| notification-service → all | `${ORDER_SERVICE_URL:http://localhost:8083}`, etc. | `ORDER_SERVICE_URL: "http://order-service:8083"`, etc. |

Spring relaxed binding maps `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` → `spring.security.oauth2.resourceserver.jwt.issuer-uri`, so api-gateway's JWT validation also uses K8s Keycloak URL.

Task 1 is a verification task — if all checks pass, document it and move on.

### ReconciliationService — RestClient Pattern

Follow **exact** pattern from `HealthAggregatorService.java` (`backend/notification-service/src/main/java/com/robomart/notification/service/HealthAggregatorService.java`):
- `@PostConstruct` to build `RestClient` instances per service
- Use `SimpleClientHttpRequestFactory` with short connect/read timeout (3s connect, 10s read)
- Handle `RestClientException` with `try/catch` → log WARN and continue (service unavailable is non-fatal for reconciliation)
- Get service URLs from `@Value("${notification.*.url}")` properties

### Reconciliation Logic — Inventory

Expected vs Actual calculation:
- **Expected reserved** (from order-service): sum of `items.quantity` for all orders in statuses `INVENTORY_RESERVING`, `PAYMENT_PROCESSING`, `CONFIRMED`, `PAYMENT_PENDING` (orders where inventory is logically reserved but not yet released)
- **Actual reserved** (from inventory-service): `inventoryItem.reservedQuantity` per product
- Discrepancy: `|actual - expected| > threshold`

Statuses where inventory is still reserved (do NOT include `CANCELLED`, `PAYMENT_REFUNDING`, `INVENTORY_RELEASING`, `DELIVERED`):

### AuditAspect — common-lib Autoconfiguration

Check `backend/common-lib/src/main/resources/META-INF/` for existing Spring Boot autoconfiguration files. Common-lib already registers `JacksonConfig`, `LoggingConfig`, `TracingConfig`, `GlobalExceptionHandler`, `CorrelationIdFilter` as components.

The `AuditAspect` must be registered as a Spring bean in each service. Options:
1. If common-lib uses `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — add an `AuditAutoConfiguration` class there
2. If services component-scan `com.robomart.common.*` via `@SpringBootApplication(scanBasePackages = {...})` — add package to scan
3. **Simplest**: Check how `CorrelationIdFilter` is registered in common-lib. Replicate the same mechanism for `AuditAspect`.

The `AuditAspect` should also be in `com.robomart.common.audit` package. If common-lib is scanned by services, it auto-discovers.

**Read** `backend/common-lib/src/main/resources/META-INF/` to confirm mechanism before implementing.

### AuditAspect — AOP Dependency

Spring AOP (used by `@Aspect`) requires `spring-aop` on the classpath. All services already use `spring-boot-starter-data-jpa` which transitively includes `spring-aop`. No additional dependency needed.

For `@Aspect` annotation: import `org.aspectj.lang.annotation.Aspect` — requires `aspectjweaver` on classpath. Check `backend/common-lib/pom.xml` for `spring-boot-starter-aop` or `aspectjweaver`. If missing, add `spring-boot-starter-aop` to common-lib's dependencies.

### EntityId Extraction from Method Return Values

The aspect needs to extract an `entityId` from the method return value to record in the audit log. Strategy:

1. If return type is an entity implementing `EntityIdProvider` → call `getEntityId()`
2. If return type is a `Long` or `Integer` → `String.valueOf(returnValue)`
3. If return type is a Record/DTO → try to call `.id()` via reflection (optional, fragile)
4. Fallback: `String.valueOf(returnValue)` truncated to 255 chars

**Alternative**: Add an `entityId` parameter to `@Auditable` annotation that accepts a Spring EL expression to extract the ID from method parameters:
```java
@Auditable(action = AuditAction.CREATE, entityType = "ORDER", entityIdExpression = "#result.id")
public Order createOrder(...) { ... }
```
This is more powerful. Use Spring's `ExpressionParser` in the aspect to evaluate. This is the pattern used by Spring Security `@PreAuthorize` for method arguments.

**Recommended**: Use the SpEL expression approach (`entityIdExpression`). Check if result is available via `#result` in the SpEL context. Use `StandardEvaluationContext` with method result as `result` variable.

### Scheduling in notification-service

`backend/notification-service/src/main/java/com/robomart/notification/config/SchedulingConfig.java` — read this file. It uses `@Configuration @EnableScheduling`. `HealthPushScheduler.java` and `CartExpiryWarningScheduler.java` (in cart-service) show the `@Scheduled` pattern.

Reconciliation jobs use `cron` expression (not `fixedDelay`) because they're daily jobs at specific times. Cron format: `"second minute hour day-of-month month day-of-week"`.

### DB Migration Versioning — Critical

Verify current migration version count before adding V2, V4, V6:

| Service | Current Last Version | Next Version for Audit Log |
|---------|---------------------|--------------------------|
| order-service | V3 (`V3__add_saga_phase_b_columns.sql`) | **V4** |
| inventory-service | V1 (`V1__init_inventory_schema.sql`) | **V2** |
| payment-service | V1 (`V1__init_payment_schema.sql`) | **V2** |
| product-service | V5 (`V5__add_product_active_column.sql`) | **V6** |

**IMPORTANT**: Verify by running `ls backend/{service}/src/main/resources/db/migration/` before creating migration files. Do NOT create a version that already exists — Flyway throws `FlywayException` on startup.

### Testing Patterns (Spring Boot 4 consistency)

- `@MockitoBean` (NOT deprecated `@MockBean`) from `org.springframework.test.context.bean.override.mockito`
- For `AuditAspect` test: use `@ExtendWith(SpringExtension.class)` + minimal context with `AuditAspect` + test target class
- No `@AutoConfigureMockMvc` (removed in Spring Boot 4)
- Test naming: `should{Expected}When{Condition}()`
- Use `AssertJ` for all assertions

### Checkstyle — No Wildcard Imports

All new Java files must use explicit imports (no `*`). Checkstyle rule: `UnusedImports` + `AvoidStarImport`. Follow pattern from existing files in the codebase (e.g., `SagaAuditLog.java` — explicit imports only).

### Project Structure Notes

**New files:**
- `backend/common-lib/src/main/java/com/robomart/common/audit/Auditable.java`
- `backend/common-lib/src/main/java/com/robomart/common/audit/AuditAction.java`
- `backend/common-lib/src/main/java/com/robomart/common/audit/AuditEvent.java`
- `backend/common-lib/src/main/java/com/robomart/common/audit/AuditAspect.java`
- `backend/common-lib/src/main/java/com/robomart/common/audit/AuditEventListener.java`
- `backend/common-lib/src/main/java/com/robomart/common/audit/EntityIdProvider.java`
- `backend/common-lib/src/test/java/com/robomart/common/audit/AuditAspectTest.java`
- `backend/order-service/src/main/resources/db/migration/V4__create_audit_log_table.sql`
- `backend/order-service/src/main/java/com/robomart/order/entity/AuditLog.java`
- `backend/order-service/src/main/java/com/robomart/order/repository/AuditLogRepository.java`
- `backend/order-service/src/main/java/com/robomart/order/service/AuditLogService.java`
- `backend/inventory-service/src/main/resources/db/migration/V2__create_audit_log_table.sql`
- `backend/inventory-service/src/main/java/com/robomart/inventory/entity/AuditLog.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/repository/AuditLogRepository.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/service/AuditLogService.java`
- `backend/payment-service/src/main/resources/db/migration/V2__create_audit_log_table.sql`
- `backend/payment-service/src/main/java/com/robomart/payment/entity/AuditLog.java`
- `backend/payment-service/src/main/java/com/robomart/payment/repository/AuditLogRepository.java`
- `backend/payment-service/src/main/java/com/robomart/payment/service/AuditLogService.java`
- `backend/product-service/src/main/resources/db/migration/V6__create_audit_log_table.sql`
- `backend/product-service/src/main/java/com/robomart/product/entity/AuditLog.java`
- `backend/product-service/src/main/java/com/robomart/product/repository/AuditLogRepository.java`
- `backend/product-service/src/main/java/com/robomart/product/service/AuditLogService.java`
- `backend/notification-service/src/main/java/com/robomart/notification/service/ReconciliationService.java`
- `backend/notification-service/src/main/java/com/robomart/notification/service/ReconciliationScheduler.java`
- `backend/notification-service/src/main/java/com/robomart/notification/controller/ReconciliationAdminRestController.java`
- `backend/notification-service/src/main/java/com/robomart/notification/service/AuditAggregatorService.java`
- `backend/notification-service/src/main/java/com/robomart/notification/controller/AuditAdminRestController.java`
- `backend/notification-service/src/test/java/com/robomart/notification/unit/service/ReconciliationServiceTest.java`

**Modified files:**
- `backend/notification-service/src/main/resources/application.yml` — add reconciliation config
- `backend/order-service/src/main/java/com/robomart/order/controller/` — add audit-logs endpoint
- `backend/inventory-service/src/main/java/com/robomart/inventory/controller/InventoryAdminRestController.java` — add reconciliation summary + audit-logs endpoints
- `backend/payment-service/src/main/java/com/robomart/payment/controller/` — add reconciliation summary + audit-logs endpoints
- `backend/product-service/src/main/java/com/robomart/product/service/ProductService.java` — add `@Auditable` annotations
- `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java` or `service/OrderService.java` — add `@Auditable` annotations
- `backend/inventory-service/src/main/java/com/robomart/inventory/service/InventoryService.java` — add `@Auditable` annotations
- `backend/payment-service/src/main/java/com/robomart/payment/service/PaymentService.java` — add `@Auditable` annotations
- `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java` — add reconciliation push method

### References

- Story 9.3 requirements: `_bmad-output/planning-artifacts/epics.md` (Epic 9, Story 9.3, lines 1650-1677)
- Story 9.2 dev notes (K8s ConfigMap + service discovery already done): `_bmad-output/implementation-artifacts/9-2-implement-health-checks-centralized-configuration.md`
- K8s ConfigMap (service URLs already configured): `infra/k8s/base/configmap.yml`
- order-service application.yml (gRPC client env var interpolation): `backend/order-service/src/main/resources/application.yml:29-33`
- notification-service application.yml (service URL mapping): `backend/notification-service/src/main/resources/application.yml:63-77`
- `HealthAggregatorService.java` (RestClient fan-out pattern): `backend/notification-service/src/main/java/com/robomart/notification/service/HealthAggregatorService.java`
- `AdminPushService.java` (WebSocket alert pattern): `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java`
- `DeadSagaDetectionJob.java` (@Scheduled pattern): `backend/order-service/src/main/java/com/robomart/order/saga/DeadSagaDetectionJob.java`
- `SagaAuditLog.java` (entity pattern, explicit imports, no Lombok): `backend/order-service/src/main/java/com/robomart/order/entity/SagaAuditLog.java`
- `CorrelationIdFilter.java` (common-lib autoconfiguration): `backend/common-lib/src/main/java/com/robomart/common/filter/CorrelationIdFilter.java`
- Inventory schema (V1 tables for context): `backend/inventory-service/src/main/resources/db/migration/V1__init_inventory_schema.sql`
- Architecture — NFR18 audit trail: `_bmad-output/planning-artifacts/architecture.md` (line 118)

## Dev Agent Record

### Agent Model Used

_claude-sonnet-4-6_

### Debug Log References

### Completion Notes List

### File List
