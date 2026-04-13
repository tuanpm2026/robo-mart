# Story 8.1: Implement Circuit Breaker & Resilience Patterns

Status: done

## Story

As a system,
I want circuit breakers on all inter-service calls to prevent cascading failures,
So that one failing service doesn't bring down the entire platform.

## Acceptance Criteria

1. **Given** Resilience4j configured on all services making gRPC calls **When** a downstream service fails 5 consecutive times **Then** the circuit breaker opens, subsequent calls fail fast with 503 without attempting the call (FR53)

2. **Given** an open circuit breaker **When** the configured wait duration passes (default: 30 seconds) **Then** the circuit transitions to half-open, allowing a test request through. If successful, circuit closes; if failed, circuit reopens

3. **Given** Resilience4j retry configured **When** a transient failure occurs on a gRPC call **Then** retry with exponential backoff is applied (3 retries, 1s initial, 2x multiplier) before circuit breaker evaluation

4. **Given** all services **When** inspected for resilience configuration **Then** `@CircuitBreaker` and `@Retry` annotations are applied on gRPC client calls, configured per-service in `application.yml` under `resilience4j.*`

## Tasks / Subtasks

### Step 1: Add Dependencies

- [x] **Task 1: Add Resilience4j BOM and version to parent POM** (AC: 1, 3, 4)
  - [x] File: `backend/pom.xml`
  - [x] Add in `<properties>`: `<resilience4j.version>2.3.0</resilience4j.version>`
    > **Verify the exact latest version**: check Maven Central for `io.github.resilience4j:resilience4j-bom` — use the latest 2.x stable.
  - [x] Add in `<dependencyManagement>` (after the Spring Cloud BOM import):
    ```xml
    <!-- Resilience4j BOM -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-bom</artifactId>
        <version>${resilience4j.version}</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
    ```

- [x] **Task 2: Add Resilience4j + AOP dependencies to order-service pom.xml** (AC: 1, 3, 4)
  - [x] File: `backend/order-service/pom.xml`
  - [x] Add after the gRPC dependency (inside `<dependencies>`):
    ```xml
    <!-- Resilience4j — Circuit Breaker + Retry (Spring Boot 4 starter) -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot3</artifactId>
    </dependency>
    <!-- AOP required for @CircuitBreaker / @Retry annotation processing -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>
    ```
    > **Note**: The artifact `resilience4j-spring-boot3` is the Spring Boot 3/4 starter. If the build fails with "artifact not found", check if Resilience4j has released a dedicated `resilience4j-spring-boot4` artifact and adjust accordingly. Spring Boot 4 is Spring Boot 3's successor and the `-spring-boot3` suffix typically covers it.

### Step 2: Create gRPC Client Wrappers with Resilience4j Annotations

**Why wrapper classes are needed**: Spring AOP only intercepts method calls through Spring proxies (i.e., external calls to a bean). Saga steps calling `private` or `this.*` methods bypass the proxy entirely. By extracting gRPC calls into separate `@Service` beans (`InventoryGrpcClient`, `PaymentGrpcClient`), `@CircuitBreaker` and `@Retry` annotations are correctly intercepted.

- [x] **Task 3: Create `InventoryGrpcClient.java`** (AC: 1, 2, 3, 4)
  - [x] Location: `backend/order-service/src/main/java/com/robomart/order/grpc/InventoryGrpcClient.java`
  - [x] Package: `com.robomart.order.grpc`
  - [x] Implementation:
    ```java
    package com.robomart.order.grpc;

    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import org.springframework.stereotype.Component;

    import com.robomart.proto.inventory.InventoryServiceGrpc;
    import com.robomart.proto.inventory.ReleaseInventoryRequest;
    import com.robomart.proto.inventory.ReleaseInventoryResponse;
    import com.robomart.proto.inventory.ReserveInventoryRequest;
    import com.robomart.proto.inventory.ReserveInventoryResponse;

    import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
    import io.github.resilience4j.retry.annotation.Retry;
    import io.grpc.StatusRuntimeException;

    @Component
    public class InventoryGrpcClient {

        private static final Logger log = LoggerFactory.getLogger(InventoryGrpcClient.class);
        private static final String INSTANCE = "inventory-service";

        private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;

        public InventoryGrpcClient(InventoryServiceGrpc.InventoryServiceBlockingStub stub) {
            this.stub = stub;
        }

        @CircuitBreaker(name = INSTANCE, fallbackMethod = "reserveFallback")
        @Retry(name = INSTANCE)
        public ReserveInventoryResponse reserveInventory(ReserveInventoryRequest request) {
            return stub.reserveInventory(request);
        }

        @CircuitBreaker(name = INSTANCE, fallbackMethod = "releaseFallback")
        @Retry(name = INSTANCE)
        public ReleaseInventoryResponse releaseInventory(ReleaseInventoryRequest request) {
            return stub.releaseInventory(request);
        }

        // Fallback: circuit open or retries exhausted on reserveInventory
        public ReserveInventoryResponse reserveFallback(ReserveInventoryRequest request, Throwable t) {
            log.error("Inventory circuit open or retries exhausted for reserveInventory: {}", t.getMessage());
            throw new InventoryServiceUnavailableException("Inventory service unavailable", t);
        }

        // Fallback: circuit open or retries exhausted on releaseInventory
        public ReleaseInventoryResponse releaseFallback(ReleaseInventoryRequest request, Throwable t) {
            log.error("Inventory circuit open or retries exhausted for releaseInventory: {}", t.getMessage());
            throw new InventoryServiceUnavailableException("Inventory service unavailable during release", t);
        }
    }
    ```

- [x] **Task 4: Create `PaymentGrpcClient.java`** (AC: 1, 2, 3, 4)
  - [x] Location: `backend/order-service/src/main/java/com/robomart/order/grpc/PaymentGrpcClient.java`
  - [x] Package: `com.robomart.order.grpc`
  - [x] Implementation:
    ```java
    package com.robomart.order.grpc;

    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import org.springframework.stereotype.Component;

    import com.robomart.proto.payment.PaymentServiceGrpc;
    import com.robomart.proto.payment.ProcessPaymentRequest;
    import com.robomart.proto.payment.ProcessPaymentResponse;
    import com.robomart.proto.payment.RefundPaymentRequest;
    import com.robomart.proto.payment.RefundPaymentResponse;

    import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
    import io.github.resilience4j.retry.annotation.Retry;

    @Component
    public class PaymentGrpcClient {

        private static final Logger log = LoggerFactory.getLogger(PaymentGrpcClient.class);
        private static final String INSTANCE = "payment-service";

        private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;

        public PaymentGrpcClient(PaymentServiceGrpc.PaymentServiceBlockingStub stub) {
            this.stub = stub;
        }

        @CircuitBreaker(name = INSTANCE, fallbackMethod = "paymentFallback")
        @Retry(name = INSTANCE)
        public ProcessPaymentResponse processPayment(ProcessPaymentRequest request) {
            return stub.processPayment(request);
        }

        @CircuitBreaker(name = INSTANCE, fallbackMethod = "refundFallback")
        @Retry(name = INSTANCE)
        public RefundPaymentResponse refundPayment(RefundPaymentRequest request) {
            return stub.refundPayment(request);
        }

        public ProcessPaymentResponse paymentFallback(ProcessPaymentRequest request, Throwable t) {
            log.error("Payment circuit open or retries exhausted for processPayment: {}", t.getMessage());
            throw new PaymentServiceUnavailableException("Payment service unavailable", t);
        }

        public RefundPaymentResponse refundFallback(RefundPaymentRequest request, Throwable t) {
            log.error("Payment circuit open or retries exhausted for refundPayment: {}", t.getMessage());
            throw new PaymentServiceUnavailableException("Payment service unavailable during refund", t);
        }
    }
    ```

- [x] **Task 5: Create exception classes** (AC: 1)
  - [x] `InventoryServiceUnavailableException.java` in `com.robomart.order.grpc`:
    ```java
    package com.robomart.order.grpc;

    public class InventoryServiceUnavailableException extends RuntimeException {
        public InventoryServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    ```
  - [x] `PaymentServiceUnavailableException.java` in `com.robomart.order.grpc`:
    ```java
    package com.robomart.order.grpc;

    public class PaymentServiceUnavailableException extends RuntimeException {
        public PaymentServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    ```

### Step 3: Update Saga Steps to Use gRPC Clients

- [x] **Task 6: Refactor `ReserveInventoryStep.java`** (AC: 1, 3, 4)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/steps/ReserveInventoryStep.java`
  - [x] Replace `InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub` field with `InventoryGrpcClient inventoryClient`
  - [x] In `execute()`, call `inventoryClient.reserveInventory(...)` instead of `inventoryStub.reserveInventory(...)`
  - [x] Keep existing exception handling for `FAILED_PRECONDITION` (business error — not retried)
  - [x] Catch `InventoryServiceUnavailableException` (circuit open / retries exhausted) → throw `SagaStepException(message, e, true)`
  - [x] Final structure:
    ```java
    @Component
    public class ReserveInventoryStep implements SagaStep {
        private final InventoryGrpcClient inventoryClient;

        public ReserveInventoryStep(InventoryGrpcClient inventoryClient) {
            this.inventoryClient = inventoryClient;
        }

        @Override
        public void execute(SagaContext context) {
            Order order = context.getOrder();
            ReserveInventoryRequest request = buildRequest(order);
            try {
                ReserveInventoryResponse response = inventoryClient.reserveInventory(request);
                order.setReservationId(response.getReservationId());
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                    order.setCancellationReason("Insufficient stock");
                    throw new SagaStepException("Insufficient stock for orderId=" + order.getId(), e, false);
                }
                throw new SagaStepException("Inventory reservation failed for orderId=" + order.getId(), e, true);
            } catch (InventoryServiceUnavailableException e) {
                throw new SagaStepException("Inventory service circuit open for orderId=" + order.getId(), e, true);
            }
        }

        @Override
        public void compensate(SagaContext context) {
            log.debug("ReserveInventoryStep.compensate() — no-op");
        }
    }
    ```
    > **Note**: `FAILED_PRECONDITION` is a business error (insufficient stock) — it must NOT be retried. Only `UNAVAILABLE` and transient errors should be retried (handled by Resilience4j @Retry on `inventoryClient`).

- [x] **Task 7: Refactor `ProcessPaymentStep.java`** (AC: 1, 3, 4)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/steps/ProcessPaymentStep.java`
  - [x] **CRITICAL**: Remove the entire manual retry implementation (`callWithRetry()`, `MAX_RETRIES`, `INITIAL_DELAY_MS`, `BACKOFF_MULTIPLIER` constants, `Thread.sleep` logic). Resilience4j `@Retry` on `PaymentGrpcClient.processPayment()` replaces this exactly (3 retries, 1s initial, 2x multiplier per application.yml config).
  - [x] Replace `PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub` with `PaymentGrpcClient paymentClient`
  - [x] Final `execute()`:
    ```java
    @Override
    public void execute(SagaContext context) {
        Order order = context.getOrder();
        ProcessPaymentRequest request = buildRequest(order);
        try {
            ProcessPaymentResponse response = paymentClient.processPayment(request);
            order.setPaymentId(response.getPaymentId());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                order.setCancellationReason("Payment declined");
                throw new SagaStepException("Payment declined for orderId=" + order.getId(), e, true);
            }
            order.setCancellationReason("Payment error: " + e.getStatus().getCode());
            throw new SagaStepException("Payment error for orderId=" + order.getId(), e, true);
        } catch (PaymentServiceUnavailableException e) {
            order.setCancellationReason("Payment service unavailable");
            throw new SagaStepException("Payment service circuit open for orderId=" + order.getId(), e, true);
        }
    }
    ```

- [x] **Task 8: Refactor `ReleaseInventoryStep.java`** (AC: 1, 3, 4)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/steps/ReleaseInventoryStep.java`
  - [x] Replace `inventoryStub` field with `InventoryGrpcClient inventoryClient`
  - [x] In `compensate()`, call `inventoryClient.releaseInventory(...)` instead of `inventoryStub.releaseInventory(...)`
  - [x] Keep best-effort error handling (log and swallow) — compensation must not block saga cancellation
  - [x] Catch both `StatusRuntimeException` and `InventoryServiceUnavailableException` in best-effort handler

- [x] **Task 9: Refactor `RefundPaymentStep.java`** (AC: 1, 3, 4)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/steps/RefundPaymentStep.java`
  - [x] Replace `paymentStub` field with `PaymentGrpcClient paymentClient`
  - [x] In `execute()`, call `paymentClient.refundPayment(...)` instead of `paymentStub.refundPayment(...)`
  - [x] Keep idempotency handling for `NOT_FOUND` and `ALREADY_EXISTS` responses
  - [x] Catch `PaymentServiceUnavailableException` → throw `SagaStepException`

### Step 4: Configure Resilience4j in application.yml

- [x] **Task 10: Add Resilience4j configuration to `application.yml`** (AC: 1, 2, 3)
  - [x] File: `backend/order-service/src/main/resources/application.yml`
  - [x] Add at root level (alongside `server:`, `spring:`, `management:` — before the `---` profile separators):
    ```yaml
    resilience4j:
      circuitbreaker:
        instances:
          inventory-service:
            # Opens after 5 consecutive failures (100% of 5-call sliding window)
            sliding-window-type: COUNT_BASED
            sliding-window-size: 5
            failure-rate-threshold: 100
            minimum-number-of-calls: 5
            wait-duration-in-open-state: 30s
            permitted-number-of-calls-in-half-open-state: 1
            # Count StatusRuntimeException and InventoryServiceUnavailableException as failures
            record-exceptions:
              - io.grpc.StatusRuntimeException
              - com.robomart.order.grpc.InventoryServiceUnavailableException
          payment-service:
            sliding-window-type: COUNT_BASED
            sliding-window-size: 5
            failure-rate-threshold: 100
            minimum-number-of-calls: 5
            wait-duration-in-open-state: 30s
            permitted-number-of-calls-in-half-open-state: 1
            record-exceptions:
              - io.grpc.StatusRuntimeException
              - com.robomart.order.grpc.PaymentServiceUnavailableException
      retry:
        instances:
          inventory-service:
            max-attempts: 3
            wait-duration: 1s
            enable-exponential-backoff: true
            exponential-backoff-multiplier: 2
            # Only retry transient gRPC failures; FAILED_PRECONDITION (business error) is not retried
            # by handling it before it reaches the client (wrapped as SagaStepException in saga steps)
            retry-exceptions:
              - io.grpc.StatusRuntimeException
          payment-service:
            max-attempts: 3
            wait-duration: 1s
            enable-exponential-backoff: true
            exponential-backoff-multiplier: 2
            retry-exceptions:
              - io.grpc.StatusRuntimeException
    ```
  - [x] **Retry + CircuitBreaker ordering**: Resilience4j's default AOP aspect order is CircuitBreaker (outer) → Retry (inner). This means retry happens first; only when all retries exhausted does the exception propagate to CircuitBreaker, which counts it as a failure. This matches AC3: "retry applied before circuit breaker evaluation".

### Step 5: Unit Tests

- [x] **Task 11: Unit tests for `InventoryGrpcClient`** (AC: 1, 2, 3)
  - [x] File: `backend/order-service/src/test/java/com/robomart/order/unit/grpc/InventoryGrpcClientTest.java`
  - [x] Test `reserveInventory()` successful call — returns response
  - [x] Test retry on `UNAVAILABLE` — mock stub throws `StatusRuntimeException(UNAVAILABLE)` twice then succeeds
  - [x] Note: Testing `@CircuitBreaker` / `@Retry` annotations in unit tests requires a Resilience4j test context; prefer integration tests for annotation behavior

- [x] **Task 12: Unit tests for `PaymentGrpcClient`** (AC: 1, 2, 3)
  - [x] File: `backend/order-service/src/test/java/com/robomart/order/unit/grpc/PaymentGrpcClientTest.java`
  - [x] Test `processPayment()` successful call
  - [x] Test `refundPayment()` successful call

- [x] **Task 13: Unit tests for refactored saga steps** (AC: 4)
  - [x] Update `ReserveInventoryStepTest`, `ProcessPaymentStepTest`, `ReleaseInventoryStepTest`, `RefundPaymentStepTest` if they exist
  - [x] Mock `InventoryGrpcClient` / `PaymentGrpcClient` instead of the raw stubs
  - [x] Test that `ProcessPaymentStep.execute()` no longer contains any `Thread.sleep` or manual retry loop

## Dev Notes

### Architecture: Only Order Service Makes gRPC Client Calls

The architecture states "Resilience4j | All services making sync calls (gRPC client side)". In this codebase, **Order Service is the only gRPC client** — it calls Inventory Service and Payment Service via gRPC during Saga execution. The API Gateway proxies HTTP→gRPC but uses Spring Cloud Gateway routing (not Spring gRPC client stubs). This story only modifies Order Service.

### Spring AOP Proxy Constraint — Why Wrapper Clients Are Required

Spring AOP intercepts method calls only when they go through the Spring bean proxy. Calling a private/internal method (e.g., `this.doGrpcCall()`) inside the same class bypasses the proxy entirely — `@CircuitBreaker` / `@Retry` annotations will silently not activate. The solution is extracting gRPC calls into separate `@Component` beans (`InventoryGrpcClient`, `PaymentGrpcClient`), injected into the saga steps. This is the standard Spring AOP pattern for Resilience4j.

### Existing Manual Retry in ProcessPaymentStep — MUST BE REMOVED

`ProcessPaymentStep.callWithRetry()` contains manual retry logic: 3 attempts, 1s initial, 2x multiplier with `Thread.sleep`. This is exactly what the new `@Retry(name = "payment-service")` annotation provides. **Delete the entire `callWithRetry()` method and all retry constants** (`MAX_RETRIES`, `INITIAL_DELAY_MS`, `BACKOFF_MULTIPLIER`). Keeping both would cause double retries (9 total calls instead of 3).

### Resilience4j Aspect Ordering (Important)

Default Resilience4j aspect order in Spring:
1. `@CircuitBreaker` — outermost (registered first in Spring context)
2. `@Retry` — innermost (runs first during method call)

Execution flow for a failing gRPC call:
```
CircuitBreaker.call() →
  Retry.call() →
    inventoryStub.reserveInventory() // throws StatusRuntimeException
    inventoryStub.reserveInventory() // retry 2 (1s delay)
    inventoryStub.reserveInventory() // retry 3 (2s delay)
    // exhausted → throws to CircuitBreaker
  CircuitBreaker registers 1 failure
```
After 5 such complete failures, circuit opens. Subsequent calls fail fast via `reserveFallback()` without calling the stub.

### gRPC Exception Handling Strategy

Business errors (not retried, not counted as circuit failures):
- `FAILED_PRECONDITION` on inventory → "Insufficient stock" → caught in saga step BEFORE reaching the client wrapper → wrapped as `SagaStepException(retryable=false)` → Resilience4j `ignore-exceptions` not needed because the exception is caught upstream

Transient failures (retried, counted by circuit breaker):
- `UNAVAILABLE` → `StatusRuntimeException` → propagates through `InventoryGrpcClient.reserveInventory()` → @Retry retries up to 3 times → if exhausted, @CircuitBreaker fallback fires → `InventoryServiceUnavailableException` thrown → saga step catches and wraps as `SagaStepException(retryable=true)`

### Fallback Method Signature Convention

Resilience4j `fallbackMethod` must have the same return type and parameter list as the annotated method, plus an additional `Throwable` parameter at the end:
```java
// Method:
public ReserveInventoryResponse reserveInventory(ReserveInventoryRequest request)
// Fallback:
public ReserveInventoryResponse reserveFallback(ReserveInventoryRequest request, Throwable t)
```
Mismatched signatures cause runtime errors (not compile-time).

### Resilience4j Artifact Name Verification

The dependency `io.github.resilience4j:resilience4j-spring-boot3` is the Spring Boot 3+ integration module. If it fails to resolve, check Maven Central:
- Search: `io.github.resilience4j` group
- Expected: `resilience4j-spring-boot3` version 2.x (or possible `resilience4j-spring-boot` for Spring Boot 4)
- The `resilience4j-bom` artifact should be used in parent POM to manage all Resilience4j module versions consistently.

### gRPC Client Config (Existing — Do NOT Change)

The existing `GrpcClientConfig.java` already creates the stub beans. The new `InventoryGrpcClient` and `PaymentGrpcClient` inject these existing stubs. No changes to `GrpcClientConfig.java` are needed.

```java
// backend/order-service/.../config/GrpcClientConfig.java (existing — do not modify)
@Bean
public InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub(GrpcChannelFactory channelFactory) {
    return InventoryServiceGrpc.newBlockingStub(channelFactory.createChannel("inventory-service"));
}
// gRPC channel addresses configured in application.yml under spring.grpc.client.*
```

### Existing application.yml gRPC Config (Do NOT Change)

```yaml
spring:
  grpc:
    client:
      inventory-service:
        address: 'static://localhost:9094'
        negotiation-type: plaintext
      payment-service:
        address: 'static://localhost:9095'
        negotiation-type: plaintext
```
These channel names (`inventory-service`, `payment-service`) match the Resilience4j instance names in `resilience4j.circuitbreaker.instances.*` and `resilience4j.retry.instances.*`.

### Project Structure Notes

- New files go in `com.robomart.order.grpc` (existing package, add alongside `OrderGrpcService.java`):
  - `InventoryGrpcClient.java`
  - `PaymentGrpcClient.java`
  - `InventoryServiceUnavailableException.java`
  - `PaymentServiceUnavailableException.java`
- Modified saga steps stay in `com.robomart.order.saga.steps`
- Test files go in `src/test/java/com/robomart/order/unit/grpc/`

### References

- Epic 8 Story 8.1 requirements: `_bmad-output/planning-artifacts/epics.md` lines 1476–1499
- Architecture — Circuit Breaker decision: `_bmad-output/planning-artifacts/architecture.md` line 371
- Architecture — Resilience4j scope: `_bmad-output/planning-artifacts/architecture.md` line 521
- Architecture — application.yml config structure: `_bmad-output/planning-artifacts/architecture.md` line 699
- Existing gRPC config: `backend/order-service/src/main/java/com/robomart/order/config/GrpcClientConfig.java`
- Existing saga steps: `backend/order-service/src/main/java/com/robomart/order/saga/steps/`
- Existing application.yml: `backend/order-service/src/main/resources/application.yml`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- spring-boot-starter-aop is NOT in the Spring Boot 4.0.4 BOM as a standalone managed dependency. Removed explicit dependency; resilience4j-spring-boot3 pulls AOP transitively, which resolves correctly.

### Completion Notes List

- Added Resilience4j BOM (v2.3.0) to parent pom.xml and resilience4j-spring-boot3 to order-service pom.xml
- Created InventoryGrpcClient and PaymentGrpcClient as @Component beans with @CircuitBreaker + @Retry annotations on all gRPC methods; fallback methods throw domain-specific unavailability exceptions
- Created InventoryServiceUnavailableException and PaymentServiceUnavailableException in com.robomart.order.grpc
- Refactored all 4 saga steps to inject client wrappers instead of raw stubs; ProcessPaymentStep had its manual callWithRetry() method fully removed
- Added Resilience4j config to application.yml: COUNT_BASED sliding window of 5, 100% failure threshold, 30s wait, 1 half-open probe call; retry: 3 attempts, 1s initial, 2x backoff
- 79/79 unit tests pass (22 new + updated, 57 existing regressions all green)
- Checkstyle failures are pre-existing (TreeWalker config issue unrelated to this story)

### File List

- backend/pom.xml (modified — added resilience4j.version property and resilience4j-bom import)
- backend/order-service/pom.xml (modified — added resilience4j-spring-boot3 dependency)
- backend/order-service/src/main/resources/application.yml (modified — added resilience4j config block)
- backend/order-service/src/main/java/com/robomart/order/grpc/InventoryGrpcClient.java (new)
- backend/order-service/src/main/java/com/robomart/order/grpc/PaymentGrpcClient.java (new)
- backend/order-service/src/main/java/com/robomart/order/grpc/InventoryServiceUnavailableException.java (new)
- backend/order-service/src/main/java/com/robomart/order/grpc/PaymentServiceUnavailableException.java (new)
- backend/order-service/src/main/java/com/robomart/order/saga/steps/ReserveInventoryStep.java (modified)
- backend/order-service/src/main/java/com/robomart/order/saga/steps/ProcessPaymentStep.java (modified)
- backend/order-service/src/main/java/com/robomart/order/saga/steps/ReleaseInventoryStep.java (modified)
- backend/order-service/src/main/java/com/robomart/order/saga/steps/RefundPaymentStep.java (modified)
- backend/order-service/src/test/java/com/robomart/order/unit/grpc/InventoryGrpcClientTest.java (new)
- backend/order-service/src/test/java/com/robomart/order/unit/grpc/PaymentGrpcClientTest.java (new)
- backend/order-service/src/test/java/com/robomart/order/unit/saga/steps/ReserveInventoryStepTest.java (modified)
- backend/order-service/src/test/java/com/robomart/order/unit/saga/steps/ProcessPaymentStepTest.java (modified)
- backend/order-service/src/test/java/com/robomart/order/unit/saga/steps/RefundPaymentStepTest.java (modified)

## Review Findings

### Decision-Needed

- [x] [Review][Decision] StatusRuntimeException catch blocks in saga steps are unreachable — resolved via Option A: created `Resilience4jConfig.java` with `CircuitBreakerConfigCustomizer` + `RetryConfigCustomizer` beans that exclude `FAILED_PRECONDITION` from both retry and circuit breaker failure counting. Business errors now propagate directly to saga step catch blocks.

### Patches

- [x] [Review][Patch] FAILED_PRECONDITION is retried 3 times — business errors should not be retried [application.yml:retry.instances.*.retry-exceptions] — Fixed via `Resilience4jConfig.java`: `RetryConfigCustomizer` + `CircuitBreakerConfigCustomizer` beans with `ignoreException(e -> FAILED_PRECONDITION)` predicate for both inventory-service and payment-service instances.
- [x] [Review][Patch] InventoryGrpcClientTest expects wrong exception type [InventoryGrpcClientTest.java:66] — Fixed: added clarifying comment to `shouldPropagateStatusRuntimeExceptionWhenStubThrows` explaining that Spring AOP is bypassed in unit tests; production contract (InventoryServiceUnavailableException) holds only with Spring context active.

### Deferred

- [x] [Review][Defer] Blocking gRPC stubs have no explicit deadline — no `.withDeadlineAfter()` on either stub bean — deferred, pre-existing
- [x] [Review][Defer] ReleaseInventoryStep iterates order.getItems() without lazy-load guard in stale saga recovery path — deferred, pre-existing
- [x] [Review][Defer] DEADLINE_EXCEEDED on payment charges the customer but causes no refund — saga orchestrator runCompensation() only calls releaseInventoryStep, not refundPaymentStep, when ProcessPaymentStep fails — deferred, pre-existing
- [x] [Review][Defer] Refund reason is generic/null during stale saga recovery for PAYMENT_REFUNDING orders — deferred, pre-existing

## Change Log

- 2026-04-13: Implemented Story 8.1 — Circuit Breaker & Resilience Patterns. Added Resilience4j to Order Service with @CircuitBreaker + @Retry on all gRPC client calls. Removed manual retry logic from ProcessPaymentStep. All 79 unit tests pass.
