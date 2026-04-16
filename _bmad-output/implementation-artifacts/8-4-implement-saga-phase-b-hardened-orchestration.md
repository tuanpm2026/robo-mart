# Story 8.4: Implement Saga Phase B — Hardened Orchestration

Status: in-progress

## Story

As a system,
I want the Saga orchestrator hardened with idempotent steps, timeouts, and dead saga detection,
So that the order flow is bulletproof under high concurrency and failure scenarios.

## Acceptance Criteria

1. **Given** Saga step execution
   **When** a step is retried (e.g., after service recovery)
   **Then** idempotent execution ensures the step produces the same result — no duplicate inventory reservations or payments (deduplication via saga step ID)

2. **Given** a Saga step
   **When** it exceeds its configured timeout (e.g., 10 seconds for payment)
   **Then** the step is marked as timed out and compensation is triggered

3. **Given** a scheduled dead saga detection job
   **When** it finds sagas stuck in a non-terminal state longer than threshold (default: 5 minutes)
   **Then** it triggers compensation for stuck sagas and logs the incident

4. **Given** 100 simultaneous order placements
   **When** processed concurrently
   **Then** all sagas complete or compensate correctly with no data corruption, no deadlocks, and no orphaned states (NFR6)

5. **Given** Saga audit log
   **When** inspected after hardened execution
   **Then** it records: per-step idempotency key, timeout events, dead saga detections, retry counts — full debugging context

## Tasks / Subtasks

### Part A: Database Migration

#### Task 1: Create Flyway Migration V3 (AC: 1, 2, 5)
- [x] **File**: `backend/order-service/src/main/resources/db/migration/V3__add_saga_phase_b_columns.sql`
- [x] **First**: Read existing V1 and V2 migrations to verify current column names and avoid conflicts:
  - `backend/order-service/src/main/resources/db/migration/V1__init_order_schema.sql`
  - `backend/order-service/src/main/resources/db/migration/V2__add_saga_fields_to_orders.sql`
- [x] Add new columns to `saga_audit_log`:
  ```sql
  ALTER TABLE saga_audit_log
      ADD COLUMN idempotency_key VARCHAR(200),
      ADD COLUMN timeout_at      TIMESTAMPTZ,
      ADD COLUMN retry_count     INTEGER NOT NULL DEFAULT 0;

  -- Partial index for fast idempotency lookups (only SUCCESS records matter)
  CREATE INDEX idx_saga_audit_log_idempotency
      ON saga_audit_log(idempotency_key)
      WHERE status = 'SUCCESS' AND idempotency_key IS NOT NULL;
  ```
- [x] Add `updated_at` to `orders` table if not present (required by dead saga detection):
  ```sql
  DO $$
  BEGIN
      IF NOT EXISTS (
          SELECT 1 FROM information_schema.columns
          WHERE table_name = 'orders' AND column_name = 'updated_at'
      ) THEN
          ALTER TABLE orders ADD COLUMN updated_at TIMESTAMPTZ;
          UPDATE orders SET updated_at = created_at WHERE updated_at IS NULL;
          ALTER TABLE orders ALTER COLUMN updated_at SET NOT NULL;
          ALTER TABLE orders ALTER COLUMN updated_at SET DEFAULT now();
          CREATE INDEX idx_orders_status_updated_at ON orders(status, updated_at);
      END IF;
  END $$;
  ```
- [x] **Verify migration order**: Current state is V2 — this must be V3. Run `./mvnw flyway:info -pl :order-service` to confirm.

---

### Part B: SagaAuditLog Entity & Repository (AC: 1, 5)

#### Task 2: Update `SagaAuditLog.java` Entity
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/entity/SagaAuditLog.java`
- [x] **Read the file first** to understand current field types (Instant vs LocalDateTime, Lombok vs manual getters) before adding fields.
- [x] Add three fields matching V3 migration columns:
  ```java
  @Column(name = "idempotency_key", length = 200)
  private String idempotencyKey;

  @Column(name = "timeout_at")
  private Instant timeoutAt;

  @Column(name = "retry_count", nullable = false)
  private int retryCount = 0;
  ```
- [x] Add getters/setters consistent with existing entity style (Lombok `@Data`/`@Getter`/`@Setter` or manual — match what's already there).
- [x] `Instant` type is consistent with existing `executedAt` field — do not use `LocalDateTime`.

#### Task 3: Add Idempotency Query to `SagaAuditLogRepository.java`
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/repository/SagaAuditLogRepository.java`
- [x] Add Spring Data JPA derived query:
  ```java
  boolean existsByIdempotencyKeyAndStatus(String idempotencyKey, String status);
  ```
- [x] This query powers the idempotency check — returns `true` if a SUCCESS record exists for a given step ID.

---

### Part C: Saga Configuration Properties (AC: 2, 3)

#### Task 4: Create `SagaProperties.java`
- [ ] **File**: `backend/order-service/src/main/java/com/robomart/order/config/SagaProperties.java`
- [ ] Implementation:
  ```java
  package com.robomart.order.config;

  import org.springframework.boot.context.properties.ConfigurationProperties;
  import org.springframework.stereotype.Component;

  import java.time.Duration;
  import java.util.HashMap;
  import java.util.Map;

  @Component
  @ConfigurationProperties(prefix = "saga")
  public class SagaProperties {

      private Steps steps = new Steps();
      private DeadSagaDetection deadSagaDetection = new DeadSagaDetection();

      public Steps getSteps() { return steps; }
      public void setSteps(Steps steps) { this.steps = steps; }

      public DeadSagaDetection getDeadSagaDetection() { return deadSagaDetection; }
      public void setDeadSagaDetection(DeadSagaDetection d) { this.deadSagaDetection = d; }

      public static class Steps {
          private Duration defaultTimeout = Duration.ofSeconds(10);
          private Map<String, Duration> timeouts = new HashMap<>();

          public Duration getDefaultTimeout() { return defaultTimeout; }
          public void setDefaultTimeout(Duration d) { this.defaultTimeout = d; }
          public Map<String, Duration> getTimeouts() { return timeouts; }
          public void setTimeouts(Map<String, Duration> t) { this.timeouts = t; }
      }

      public static class DeadSagaDetection {
          private boolean enabled = true;
          private Duration stuckThreshold = Duration.ofMinutes(5);
          private long checkIntervalMs = 60_000L;

          public boolean isEnabled() { return enabled; }
          public void setEnabled(boolean e) { this.enabled = e; }
          public Duration getStuckThreshold() { return stuckThreshold; }
          public void setStuckThreshold(Duration d) { this.stuckThreshold = d; }
          public long getCheckIntervalMs() { return checkIntervalMs; }
          public void setCheckIntervalMs(long ms) { this.checkIntervalMs = ms; }
      }
  }
  ```
- [x] **Check**: `@ConfigurationProperties` requires `spring-boot-configuration-processor` in pom.xml. Add if not present (optional but recommended):
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-configuration-processor</artifactId>
      <optional>true</optional>
  </dependency>
  ```

#### Task 5: Update `application.yml` — Saga Config + Scheduling
- [x] **File**: `backend/order-service/src/main/resources/application.yml`
- [x] Add saga configuration block:
  ```yaml
  saga:
    steps:
      default-timeout: 10s
      timeouts:
        # Keys must exactly match SagaStep.getName() return values — verify before deploying
        reserve-inventory: 10s
        process-payment: 10s
        release-inventory: 10s
        refund-payment: 10s
    dead-saga-detection:
      enabled: true
      stuck-threshold: 5m
      check-interval-ms: 60000
  ```
- [x] **Check**: Search `OrderServiceApplication.java` and all `@Configuration` classes for `@EnableScheduling`. Add `@EnableScheduling` to `OrderServiceApplication.java` if absent — do NOT add twice.
- [x] **Step name verification**: Before setting the timeout keys, read `SagaStep.getName()` implementations in:
  - `saga/steps/ReserveInventoryStep.java`
  - `saga/steps/ProcessPaymentStep.java`
  - `saga/steps/ReleaseInventoryStep.java`
  - `saga/steps/RefundPaymentStep.java`
  Adjust the YAML keys to exactly match those string values.

---

### Part D: Harden `OrderSagaOrchestrator.java` (AC: 1, 2, 4, 5)

#### Task 6: Add `executeStep()` Wrapper with Idempotency + Timeout
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
- [x] **Read the file first** — understand current constructor, field names, and `logSagaStep()` signature before making changes.
- [x] Inject `SagaProperties` and `SagaAuditLogRepository` (add to constructor if using constructor injection — which is the project pattern):
  ```java
  private final SagaProperties sagaProperties;
  private final SagaAuditLogRepository sagaAuditLogRepository;
  ```
- [x] Add a virtual thread executor at class level (Java 21):
  ```java
  private final ExecutorService stepExecutor =
      Executors.newVirtualThreadPerTaskExecutor();
  ```
  Add `@PreDestroy` to shut it down cleanly:
  ```java
  @PreDestroy
  public void shutdownStepExecutor() {
      stepExecutor.shutdown();
  }
  ```
- [x] Add the `executeStep()` wrapper method:
  ```java
  /**
   * Executes a saga step with idempotency check and configurable timeout.
   * Idempotency key: "{orderId}:{stepName}" — prevents duplicate step execution on retries.
   */
  private void executeStep(SagaStep step, SagaContext context, int retryCount) {
      String sagaId    = context.getOrder().getId().toString();
      String idempotencyKey = sagaId + ":" + step.getName();

      // AC1: Idempotency — skip if already succeeded
      if (sagaAuditLogRepository.existsByIdempotencyKeyAndStatus(idempotencyKey, "SUCCESS")) {
          log.info("Saga step [{}] idempotent — already succeeded for saga {}, skipping",
                   step.getName(), sagaId);
          return;
      }

      // AC2: Per-step timeout
      Duration timeout = sagaProperties.getSteps().getTimeouts()
          .getOrDefault(step.getName(), sagaProperties.getSteps().getDefaultTimeout());

      logSagaStep(context.getOrder(), sagaId, step.getName(), "STARTED",
                  idempotencyKey, null, null, retryCount);

      CompletableFuture<Void> future =
          CompletableFuture.runAsync(() -> step.execute(context), stepExecutor);

      try {
          future.get(timeout.toSeconds(), TimeUnit.SECONDS);
          logSagaStep(context.getOrder(), sagaId, step.getName(), "SUCCESS",
                      idempotencyKey, null, null, retryCount);

      } catch (TimeoutException e) {
          future.cancel(true);
          logSagaStep(context.getOrder(), sagaId, step.getName(), "TIMED_OUT",
                      idempotencyKey, Instant.now(),
                      "Step timed out after " + timeout.toSeconds() + "s", retryCount);
          throw new SagaStepException(
              "Saga step " + step.getName() + " timed out after " + timeout.toSeconds() + "s",
              null, true, false);

      } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          logSagaStep(context.getOrder(), sagaId, step.getName(), "FAILED",
                      idempotencyKey, null,
                      cause != null ? cause.getMessage() : e.getMessage(), retryCount);
          if (cause instanceof SagaStepException sse) {
              throw sse;
          }
          throw new SagaStepException(
              cause != null ? cause.getMessage() : "Step execution failed",
              cause, true, false);

      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SagaStepException("Step execution interrupted", e, true, false);
      }
  }
  ```
- [x] **Update `logSagaStep()` signature** to accept new Phase B fields. Current signature (verify from reading the file) likely is:
  `logSagaStep(Order, String stepName, String status, String request, String response, String error)`
  
  New signature:
  ```java
  private void logSagaStep(Order order, String sagaId, String stepName, String status,
                            String idempotencyKey, Instant timeoutAt, String error, int retryCount) {
      SagaAuditLog auditLog = new SagaAuditLog();
      auditLog.setSagaId(sagaId);
      auditLog.setOrderId(order.getId().toString());
      auditLog.setStepName(stepName);
      auditLog.setStatus(status);
      auditLog.setIdempotencyKey(idempotencyKey);
      auditLog.setTimeoutAt(timeoutAt);
      auditLog.setError(error);
      auditLog.setRetryCount(retryCount);
      auditLog.setExecutedAt(Instant.now());
      sagaAuditLogRepository.save(auditLog);
  }
  ```
  **IMPORTANT**: After changing the signature, update ALL existing `logSagaStep()` call sites in the class. Pass `null` for `idempotencyKey`, `null` for `timeoutAt`, and `0` for `retryCount` at all non-Phase-B call sites.

- [x] **Replace** all existing `step.execute(context)` calls in `executeSaga()` with `executeStep(step, context, 0)`.
  - Remove the existing `logSagaStep()` calls that surround `step.execute()` — `executeStep()` handles logging internally.

#### Task 7: Add `isTerminal()` to `OrderStatus` + Optimistic Lock Handling (AC: 4)
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/enums/OrderStatus.java`
- [x] Add helper:
  ```java
  public boolean isTerminal() {
      return this == CONFIRMED || this == CANCELLED || this == DELIVERED;
  }
  ```
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
- [x] Update `updateOrderStatus()` to handle optimistic lock conflicts gracefully:
  ```java
  @Transactional
  private void updateOrderStatus(Order order, OrderStatus newStatus) {
      try {
          order.setStatus(newStatus);
          orderRepository.save(order);
      } catch (ObjectOptimisticLockingFailureException e) {
          Order reloaded = orderRepository.findById(order.getId())
              .orElseThrow(() -> new IllegalStateException("Order not found: " + order.getId()));
          log.warn("Optimistic lock conflict on order {} — current: {}, attempted: {}",
                   order.getId(), reloaded.getStatus(), newStatus);
          // Accept terminal state — another thread already completed this saga
          if (!reloaded.getStatus().isTerminal()) {
              throw e;
          }
      }
  }
  ```

---

### Part E: Dead Saga Detection Job (AC: 3, 5)

#### Task 8: Add `findStuckSagas()` to `OrderRepository`
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/repository/OrderRepository.java`
- [x] **Read the file first** to check if `updatedAt` is already mapped and if similar queries exist.
- [x] If `Order.java` lacks `updatedAt`, add it:
  ```java
  @LastModifiedDate
  @Column(name = "updated_at")
  private Instant updatedAt;
  ```
  And ensure `Order.java` has `@EntityListeners(AuditingEntityListener.class)`.
  And ensure a `@Configuration` class has `@EnableJpaAuditing`.
- [x] Add query:
  ```java
  @Query("SELECT o FROM Order o WHERE o.status IN :statuses AND o.updatedAt < :cutoff")
  List<Order> findStuckSagas(
      @Param("statuses") List<OrderStatus> statuses,
      @Param("cutoff") Instant cutoff
  );
  ```

#### Task 9: Add `handleDeadSaga()` + `recoverSingleSaga()` to `OrderSagaOrchestrator`
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
- [x] Extract per-order recovery logic from existing `recoverStaleSagas()` into a new private method:
  ```java
  private void recoverSingleSaga(Order order) {
      // Move the switch/if logic that operates on a single order here
      // from the existing recoverStaleSagas() loop body
  }
  ```
- [x] Update `recoverStaleSagas()` to delegate to `recoverSingleSaga()` — keep the existing method to preserve startup recovery behavior.
- [x] Add public `handleDeadSaga()` called by the scheduled job:
  ```java
  /**
   * Triggers compensation for a stuck/dead saga detected by scheduled job.
   * Logs RECOVERY status in saga_audit_log (AC3, AC5).
   */
  public void handleDeadSaga(Order order) {
      String sagaId = order.getId().toString();
      log.warn("Dead saga detected: orderId={}, status={}", order.getId(), order.getStatus());
      logSagaStep(order, sagaId, "DEAD_SAGA_DETECTION", "RECOVERY",
                  null, null,
                  "Stuck in " + order.getStatus() + " — triggering compensation", 0);
      recoverSingleSaga(order);
  }
  ```

#### Task 10: Create `DeadSagaDetectionJob.java`
- [x] **File**: `backend/order-service/src/main/java/com/robomart/order/saga/DeadSagaDetectionJob.java`
- [x] Implementation:
  ```java
  package com.robomart.order.saga;

  import com.robomart.order.config.SagaProperties;
  import com.robomart.order.entity.Order;
  import com.robomart.order.enums.OrderStatus;
  import com.robomart.order.repository.OrderRepository;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.scheduling.annotation.Scheduled;
  import org.springframework.stereotype.Component;

  import java.time.Instant;
  import java.util.List;

  @Component
  public class DeadSagaDetectionJob {

      private static final Logger log = LoggerFactory.getLogger(DeadSagaDetectionJob.class);

      // Non-terminal states where a saga could be "stuck"
      private static final List<OrderStatus> STUCK_STATUSES = List.of(
          OrderStatus.INVENTORY_RESERVING,
          OrderStatus.PAYMENT_PROCESSING,
          OrderStatus.PAYMENT_PENDING,
          OrderStatus.PAYMENT_REFUNDING,
          OrderStatus.INVENTORY_RELEASING
      );

      private final OrderRepository orderRepository;
      private final OrderSagaOrchestrator sagaOrchestrator;
      private final SagaProperties sagaProperties;

      public DeadSagaDetectionJob(OrderRepository orderRepository,
                                   OrderSagaOrchestrator sagaOrchestrator,
                                   SagaProperties sagaProperties) {
          this.orderRepository  = orderRepository;
          this.sagaOrchestrator = sagaOrchestrator;
          this.sagaProperties   = sagaProperties;
      }

      @Scheduled(
          fixedDelayString = "${saga.dead-saga-detection.check-interval-ms:60000}",
          initialDelayString = "${saga.dead-saga-detection.initial-delay-ms:30000}"
      )
      public void detectAndRecoverDeadSagas() {
          if (!sagaProperties.getDeadSagaDetection().isEnabled()) {
              return;
          }
          Instant cutoff = Instant.now()
              .minus(sagaProperties.getDeadSagaDetection().getStuckThreshold());

          List<Order> stuckOrders = orderRepository.findStuckSagas(STUCK_STATUSES, cutoff);
          if (stuckOrders.isEmpty()) {
              return;
          }

          log.warn("Dead saga detection: {} stuck order(s) found (threshold={})",
                   stuckOrders.size(),
                   sagaProperties.getDeadSagaDetection().getStuckThreshold());

          for (Order order : stuckOrders) {
              try {
                  sagaOrchestrator.handleDeadSaga(order);
              } catch (Exception e) {
                  log.error("Recovery failed for dead saga orderId={}: {}",
                            order.getId(), e.getMessage(), e);
              }
          }
      }
  }
  ```
- [x] Add `initial-delay-ms` to `application.yml` (avoid running job during startup recovery):
  ```yaml
  saga:
    dead-saga-detection:
      initial-delay-ms: 30000
  ```

---

### Part F: Tests (AC: 1, 2, 3, 4, 5)

#### Task 11: Unit Tests — Idempotency and Timeout
- [x] **File**: `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorPhaseBTest.java`
- [x] Test idempotency skip:
  ```java
  @Test
  void stepIsSkippedWhenAlreadySucceeded() {
      // Given: SUCCESS record exists for reserve-inventory
      when(sagaAuditLogRepository.existsByIdempotencyKeyAndStatus(
          contains(":reserve-inventory"), eq("SUCCESS"))).thenReturn(true);

      // When: executeSaga called
      orchestrator.executeSaga(order);

      // Then: inventory gRPC NOT called
      verify(inventoryGrpcClient, never()).reserveInventory(any());
  }
  ```
- [x] Test timeout triggers compensation:
  ```java
  @Test
  void timeoutOnReserveInventoryTriggersCancel() throws Exception {
      // Given: step hangs
      doAnswer(inv -> { Thread.sleep(5_000); return null; })
          .when(reserveInventoryStep).execute(any());
      sagaProperties.getSteps().getTimeouts().put("reserve-inventory", Duration.ofMillis(100));

      // When: executeSaga called
      orchestrator.executeSaga(order);

      // Then: order ends up CANCELLED
      verify(orderRepository, atLeastOnce()).save(
          argThat(o -> o.getStatus() == OrderStatus.CANCELLED));

      // And: TIMED_OUT audit entry logged
      verify(sagaAuditLogRepository).save(
          argThat(l -> "TIMED_OUT".equals(l.getStatus()) && l.getTimeoutAt() != null));
  }
  ```
- [x] **Use `@MockitoBean`** from `org.springframework.test.context.bean.override.mockito` — NOT deprecated `@MockBean`.

#### Task 12: Unit Tests — Dead Saga Detection Job
- [x] **File**: `backend/order-service/src/test/java/com/robomart/order/unit/saga/DeadSagaDetectionJobTest.java`
- [x] Key tests:
  ```java
  @Test
  void detectsAndRecoversStuckSagas() {
      Order stuck = // order in PAYMENT_PROCESSING, updatedAt = 10 min ago
      when(orderRepository.findStuckSagas(any(), any())).thenReturn(List.of(stuck));

      job.detectAndRecoverDeadSagas();

      verify(sagaOrchestrator).handleDeadSaga(stuck);
  }

  @Test
  void skipsWhenDetectionDisabled() {
      sagaProperties.getDeadSagaDetection().setEnabled(false);

      job.detectAndRecoverDeadSagas();

      verifyNoInteractions(orderRepository, sagaOrchestrator);
  }

  @Test
  void continuesAfterIndividualRecoveryFailure() {
      Order order1 = // stuck order
      Order order2 = // another stuck order
      when(orderRepository.findStuckSagas(any(), any())).thenReturn(List.of(order1, order2));
      doThrow(new RuntimeException("recovery failed"))
          .when(sagaOrchestrator).handleDeadSaga(order1);

      // Should not throw — processes order2 despite order1 failure
      assertDoesNotThrow(() -> job.detectAndRecoverDeadSagas());
      verify(sagaOrchestrator).handleDeadSaga(order2);
  }
  ```

#### Task 13: Verify Existing Tests Still Pass
- [x] Run: `cd backend && ./mvnw test -pl :order-service`
- [x] Fix any compilation errors from `logSagaStep()` signature changes — all existing call sites must pass `null, null, 0` for new params.
- [x] `OrderSagaIT` (integration test with real DB + mock gRPC) must pass — the migration V3 will be applied by Testcontainers.

---

## Dev Notes

### Idempotency Key Design

Key format: `"{orderId}:{stepName}"` — e.g., `"123:reserve-inventory"`.

**Two-layer idempotency:**
1. **Saga orchestrator layer** (this story): Before calling `step.execute()`, check `saga_audit_log` for SUCCESS record with this key. If found, skip execution entirely.
2. **gRPC layer** (already exists): `ReserveInventoryStep` and `ProcessPaymentStep` pass `orderId` as idempotency key in proto requests. The downstream services handle deduplication.

This story adds layer 1. Layer 2 is pre-existing and must not be removed.

### Step Name Alignment

**Critical**: The `saga.steps.timeouts` YAML keys must exactly match `SagaStep.getName()` return values. Before writing `application.yml`:
1. Read `saga/steps/ReserveInventoryStep.java` → note `getName()` return value
2. Read `saga/steps/ProcessPaymentStep.java` → note `getName()` return value
3. Read `saga/steps/ReleaseInventoryStep.java` → note `getName()` return value
4. Read `saga/steps/RefundPaymentStep.java` → note `getName()` return value

Adjust YAML timeout keys to exactly match those strings.

### CompletableFuture Cancel Behavior

`future.cancel(true)` sends an interrupt signal but **cannot forcibly terminate a gRPC call** in progress. The virtual thread running the step may continue until the gRPC call completes (with Resilience4j's retry backoff). This is acceptable:
- The orchestrator marks the step `TIMED_OUT` and triggers compensation immediately.
- If the abandoned gRPC call eventually succeeds, idempotency keys at the gRPC layer prevent double-reservation/double-payment.

Do **not** add `future.cancel(true)` and then wait for the future — just cancel and proceed.

### Virtual Threads (Java 21)

`Executors.newVirtualThreadPerTaskExecutor()` is the correct API for Java 21. No thread pool sizing needed. Ideal for I/O-bound tasks (gRPC calls). Each saga step gets its own virtual thread.

The `stepExecutor` must be a class-level field (singleton per orchestrator bean). Add `@PreDestroy` cleanup to avoid executor leak on shutdown.

### Dead Saga Detection vs Startup Recovery

| | `recoverStaleSagas()` | `DeadSagaDetectionJob` |
|--|--|--|
| **Trigger** | `ApplicationReadyEvent` (once at startup) | `@Scheduled` every 60s |
| **Purpose** | Recover sagas interrupted by JVM crash | Detect sagas stuck during normal operation |
| **Initial delay** | Runs immediately on startup | 30s delay (avoids overlap with startup recovery) |
| **Shared logic** | Calls `recoverSingleSaga()` | Calls `handleDeadSaga()` → `recoverSingleSaga()` |

Keep both. The startup recovery handles "crash during saga" (expected). The job handles "saga stuck due to edge case" (unexpected — warrants monitoring alert).

### `updatedAt` Field Verification

**Before Task 8** — read `Order.java` and check:
1. Is there `private Instant updatedAt` (or `private LocalDateTime updatedAt`)?
2. Is there `@EntityListeners(AuditingEntityListener.class)`?
3. Is there `@EnableJpaAuditing` anywhere in the order-service config?
4. Does V1 or V2 migration have `updated_at TIMESTAMPTZ` in the `orders` table DDL?

If ALL of these are present → skip the `updated_at` section in V3 migration (the `DO $$ ... END $$` block). Otherwise add them.

### Optimistic Locking Scope

The Order entity has `@Version` (confirmed by exploration). With 100 concurrent order placements:
- Each order is a separate saga instance → no cross-order contention.
- Contention only occurs if two threads update the **same** order (e.g., dead saga detection fires while the saga is still in-flight).
- `ObjectOptimisticLockingFailureException` handling in `updateOrderStatus()` prevents crash; if the concurrent update already reached a terminal state, the losing thread accepts it gracefully.

### `logSagaStep()` Call Sites After Signature Change

After updating the signature, grep for all occurrences:
```bash
grep -n "logSagaStep(" backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java
```
Update each call site:
- For Phase A calls (existing): `logSagaStep(order, sagaId, stepName, status, null, null, "error msg", 0)`
- For Phase B calls (new): parameters are set inside `executeStep()` and `handleDeadSaga()`

### Checkstyle

New Java files must comply with `backend/config/checkstyle/checkstyle.xml`:
- Import ordering: static imports first, then `com.robomart.*`, then `org.*`, then `java.*`
- No unused imports
- Constructor injection (no `@Autowired` on fields)

Run before committing:
```bash
cd backend && ./mvnw checkstyle:check -pl :order-service
```

### Spring Boot 4 Testing Patterns (from Stories 8.2 and 8.3)

- `@MockitoBean` — `org.springframework.test.context.bean.override.mockito.MockitoBean`
- NOT `@MockBean` (deprecated in Spring Boot 4)
- `@SpringBootTest` unit tests: pass `properties` to disable JWT/external service auto-config

---

### Project Structure Notes

**New files**:
- `backend/order-service/src/main/resources/db/migration/V3__add_saga_phase_b_columns.sql`
- `backend/order-service/src/main/java/com/robomart/order/config/SagaProperties.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/DeadSagaDetectionJob.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorPhaseBTest.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/DeadSagaDetectionJobTest.java`

**Modified files**:
- `backend/order-service/src/main/java/com/robomart/order/entity/SagaAuditLog.java` — add `idempotencyKey`, `timeoutAt`, `retryCount`
- `backend/order-service/src/main/java/com/robomart/order/repository/SagaAuditLogRepository.java` — add `existsByIdempotencyKeyAndStatus()`
- `backend/order-service/src/main/java/com/robomart/order/repository/OrderRepository.java` — add `findStuckSagas()`
- `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java` — add `executeStep()`, `handleDeadSaga()`, `recoverSingleSaga()`; update `logSagaStep()` signature; add `stepExecutor` + `@PreDestroy`
- `backend/order-service/src/main/java/com/robomart/order/enums/OrderStatus.java` — add `isTerminal()`
- `backend/order-service/src/main/resources/application.yml` — add `saga:` config block; add `@EnableScheduling` to application class if missing
- *(Conditional)* `backend/order-service/src/main/java/com/robomart/order/entity/Order.java` — add `updatedAt` with `@LastModifiedDate` if absent
- *(Conditional)* `backend/order-service/pom.xml` — add `spring-boot-configuration-processor` if absent

---

### References

- Story 8.4 requirements: `_bmad-output/planning-artifacts/epics.md` (Epic 8, Story 8.4)
- NFR6 (100 concurrent orders): `_bmad-output/planning-artifacts/epics.md`
- Architecture — Saga Phase B design: `_bmad-output/planning-artifacts/architecture.md` (Saga Orchestration Design, Phase B section)
- Architecture — Circuit Breaker (Resilience4j): `_bmad-output/planning-artifacts/architecture.md` line ~371
- Existing saga orchestrator: `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
- Existing SagaAuditLog entity: `backend/order-service/src/main/java/com/robomart/order/entity/SagaAuditLog.java`
- Existing SagaStep interface: `backend/order-service/src/main/java/com/robomart/order/saga/SagaStep.java`
- Existing saga steps: `backend/order-service/src/main/java/com/robomart/order/saga/steps/`
- Existing Flyway migrations V1/V2: `backend/order-service/src/main/resources/db/migration/`
- Existing saga unit tests (patterns to follow): `backend/order-service/src/test/java/com/robomart/order/unit/saga/`
- Story 8.3 dev notes (Spring Boot 4 patterns, `@MockitoBean`): `_bmad-output/implementation-artifacts/8-3-implement-rate-limiting-graceful-shutdown.md`

## Dev Agent Record

### Agent Model Used

_claude-sonnet-4-6_

### Debug Log References

None.

### Completion Notes List

- Implemented all 13 tasks across Parts A–F.
- V3 migration skipped `DO $$ ... END $$` block — `updated_at` already present in V1 migration and `BaseEntity` (via `@UpdateTimestamp`). Added `idx_orders_status_updated_at` composite index instead.
- `logSagaStep()` signature reduced to 7 params (dropping `sagaId` — derived from `order.getId()`) to satisfy Checkstyle ParameterNumber max=7.
- `@EnableScheduling` was already present in `OrderServiceApplication.java` — not added twice.
- YAML timeout keys set to exact `getName()` values: `ReserveInventory`, `ProcessPayment`, `ReleaseInventory`, `RefundPaymentStep`.
- Updated `OrderSagaOrchestratorTest` and `OrderSagaOrchestratorCancelTest` to pass new `SagaProperties` to constructor; added lenient stub for `existsByIdempotencyKeyAndStatus` returning `false`.
- All 90 unit tests pass with 0 failures.
- `OrderSagaOrchestrator.java`: 409 lines (within 500-line FileLength limit).

### File List

**New files:**
- `backend/order-service/src/main/resources/db/migration/V3__add_saga_phase_b_columns.sql`
- `backend/order-service/src/main/java/com/robomart/order/config/SagaProperties.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/DeadSagaDetectionJob.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorPhaseBTest.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/DeadSagaDetectionJobTest.java`

**Modified files:**
- `backend/order-service/src/main/java/com/robomart/order/entity/SagaAuditLog.java`
- `backend/order-service/src/main/java/com/robomart/order/repository/SagaAuditLogRepository.java`
- `backend/order-service/src/main/java/com/robomart/order/repository/OrderRepository.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
- `backend/order-service/src/main/java/com/robomart/order/enums/OrderStatus.java`
- `backend/order-service/src/main/resources/application.yml`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorTest.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorCancelTest.java`

### Change Log

- 2026-04-16: Implemented Story 8.4 — Saga Phase B hardened orchestration: idempotency via audit log keys, per-step configurable timeouts with virtual threads, dead saga detection scheduled job, optimistic lock handling in status updates, `OrderStatus.isTerminal()` helper. 90 unit tests pass.

---

### Review Findings

#### Patches Applied
- [x] [Review][Patch] Fix timeout precision — use `toMillis()`/`MILLISECONDS` instead of `toSeconds()`/`SECONDS`; `Duration.ofMillis(100).toSeconds()` == 0 breaks tests [OrderSagaOrchestrator.java:151]
- [x] [Review][Patch] Await step executor termination in `shutdownStepExecutor()` — bare `shutdown()` abandons in-flight saga steps on pod stop [OrderSagaOrchestrator.java:87]
- [x] [Review][Patch] Add `initialDelayMs` field to `SagaProperties.DeadSagaDetection` — property exists in YAML and `@Scheduled` but not bound to the config bean [SagaProperties.java]
- [x] [Review][Patch] Remove `PAYMENT_PENDING` from `STUCK_STATUSES` — this is an intentional circuit-breaker hold state, not a dead saga; 5-minute threshold would cancel orders legitimately waiting for payment circuit recovery [DeadSagaDetectionJob.java:26]
- [x] [Review][Patch] Align `recoverSingleSaga()` else-branch to use `finalizeCancellation()` — current code calls `updateOrderStatus` + `publishStatusChangedEvent` separately, skipping the atomic outbox write that the other branches use [OrderSagaOrchestrator.java:374]
- [x] [Review][Patch] Add UNIQUE constraint on `idempotency_key` in V3 migration — without a DB-level constraint the idempotency check has a TOCTOU window: two concurrent threads can both see no SUCCESS row and both execute the step [V3__add_saga_phase_b_columns.sql]

#### Action Items
- [ ] [Review][Patch] Add result-set limit to `findStuckSagas()` — unbounded query can load thousands of stuck orders in one scheduler invocation and cascade recovery load onto downstream services [OrderRepository.java]
- [ ] [Review][Patch] Sync in-memory `order` object when optimistic lock is silently swallowed in `updateOrderStatus()` — caller continues using `order.getStatus()` which is stale after the silent accept; subsequent status checks in `recoverSingleSaga()` may act on wrong state [OrderSagaOrchestrator.java:209]
- [ ] [Review][Patch] Consider atomic idempotency guard — current check (`existsByIdempotencyKeyAndStatus`) and step execution are in separate transactions; a DB-unique constraint (above) is the primary guard but wrapping both in one transaction would eliminate the window entirely [OrderSagaOrchestrator.java:135]

#### Deferred
- [x] [Review][Defer] Multi-instance deployment races on dead saga recovery — no distributed claim/lock before `handleDeadSaga()`; optimistic locking provides partial protection but compensation steps run before any status update [DeadSagaDetectionJob.java] — deferred, requires distributed lock (Redis/Zookeeper), architectural change beyond story scope
- [x] [Review][Defer] `orderId` field always set equal to `sagaId` in `logSagaStep()` — loses future ability to distinguish multiple sagas per order [OrderSagaOrchestrator.java:249] — deferred, pre-existing design decision (sagaId == orderId by current design)
