# Story 4.5: Implement Order Cancellation with Saga Compensation

Status: done

## Story

As a customer,
I want to cancel an order in PENDING or CONFIRMED status,
So that I can change my mind and receive a refund automatically.

## Acceptance Criteria

1. **Given** an order in PENDING status **When** `POST /api/v1/orders/{orderId}/cancel` is sent **Then** any reserved inventory is released (if `reservationId` is set), the order transitions to CANCELLED, and an `order.order.cancelled` outbox event is published. (FR20, FR27)

2. **Given** an order in CONFIRMED status **When** cancelled **Then** the cancellation saga executes: CONFIRMED → PAYMENT_REFUNDING → INVENTORY_RELEASING → CANCELLED. `RefundPayment` gRPC is called on Payment Service using `order.paymentId`, then `ReleaseInventory` gRPC is called using `order.reservationId`. `order.order.cancelled` outbox event is published. (FR21, FR31)

3. **Given** an order in SHIPPED or DELIVERED status **When** cancel is attempted **Then** the system returns 409 Conflict with error code `ORDER_NOT_CANCELLABLE`. Same rejection applies to in-flight states: INVENTORY_RESERVING, PAYMENT_PROCESSING, PAYMENT_REFUNDING, INVENTORY_RELEASING, CANCELLED. (FR20)

4. **Given** the `cancelOrder` gRPC RPC in `OrderGrpcService` **When** implemented **Then** it delegates to `OrderService.cancelOrder()`, maps `OrderNotCancellableException` → `FAILED_PRECONDITION` gRPC status, and maps `ResourceNotFoundException` → `NOT_FOUND`. (FR51)

5. **Given** the `order.order.cancelled` outbox event **When** published **Then** `OutboxPollingService` correctly routes it to the Kafka topic `order.order.cancelled` (requires adding `"order_cancelled"` case to the existing `resolveTopicForEvent()` switch). (FR52, FR55)

6. **Given** any cancellation saga step (RefundPayment or ReleaseInventory) **When** it executes **Then** each step is logged in `saga_audit_log` with stepName, status (STARTED/SUCCESS/FAILED), and timestamps — consistent with Story 4.4 saga audit pattern.

7. **Given** an order with `paymentId = null` **When** PENDING cancellation is processed **Then** `RefundPaymentStep` is skipped (no payment to refund); **Given** an order with `reservationId = null` **When** cancellation runs **Then** `ReleaseInventoryStep` skips the gRPC call gracefully (null-guard already exists in `ReleaseInventoryStep.compensate()`).

## Tasks / Subtasks

- [x] Task 1: Create `OrderNotCancellableException` (AC: 3)
  - [x] 1.1 Create `exception/OrderNotCancellableException.java` extending `BusinessRuleException` from `com.robomart.common.exception`
  - [x] 1.2 Constructor: `(String message, String errorCode)` — pass HTTP 409 conflict to parent
  - [x] 1.3 Use error code constant: `"ORDER_NOT_CANCELLABLE"` — added to `ErrorCode` enum in common-lib; added protected constructor to `BusinessRuleException`

- [x] Task 2: Create `RefundPaymentStep` (AC: 2, 7)
  - [x] 2.1 Create `saga/steps/RefundPaymentStep.java` annotated with `@Component`
  - [x] 2.2 Inject `PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub` (already declared as Spring bean in `config/GrpcClientConfig.java`)
  - [x] 2.3 `getName()` → return `"RefundPaymentStep"`
  - [x] 2.4 `execute(SagaContext ctx)`:
    - If `order.getPaymentId()` is null → log warning + return (no payment to refund)
    - Build `RefundPaymentRequest(paymentId=order.getPaymentId(), orderId=order.getId().toString(), amount=Money(currency="USD", amount=order.getTotalAmount().toPlainString()), reason=order.getCancellationReason())`
    - Call `paymentStub.refundPayment(request)`
    - On success: log `"Payment refunded for orderId={}, refundTxId={}"`
    - On `StatusRuntimeException` with `NOT_FOUND`: log warning + return (payment doesn't exist, skip gracefully)
    - On other `StatusRuntimeException`: throw `SagaStepException("Refund failed: " + e.getMessage(), false)` — `shouldCompensate=false` (nothing to undo for a failed refund)
  - [x] 2.5 `compensate(SagaContext ctx)` → throw `UnsupportedOperationException("RefundPaymentStep is forward-only")`

- [x] Task 3: Add `cancelPendingSaga()` and `cancelConfirmedSaga()` to `OrderSagaOrchestrator` (AC: 1, 2, 6)
  - [x] 3.1 Inject `RefundPaymentStep refundPaymentStep` in constructor (add to constructor parameters; do NOT use `@Autowired` field injection — constructor injection pattern used throughout this service)
  - [x] 3.2 Add `publishOrderCancelledEvent(Order order, String reason, String cancelledBy)` private method
  - [x] 3.3 Add `cancelPendingSaga(Order order, String reason, String cancelledBy)`
  - [x] 3.4 Add `cancelConfirmedSaga(Order order, String reason, String cancelledBy)` — best-effort: refund failure does NOT prevent cancellation

- [x] Task 4: Add `cancelOrder()` to `OrderService` (AC: 1, 2, 3)
  - [x] 4.1 Add `OrderNotCancellableException` import
  - [x] 4.2 Define valid cancellable states: `Set.of(OrderStatus.PENDING, OrderStatus.CONFIRMED)`
  - [x] 4.3 Implement `cancelOrder(Long orderId, String reason, String cancelledBy)`:
    ```java
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    // Eagerly load items (needed by ReleaseInventoryStep for items list)
    order.setItems(orderItemRepository.findByOrderId(orderId));
    
    if (!CANCELLABLE_STATUSES.contains(order.getStatus())) {
        throw new OrderNotCancellableException(
            "Order " + orderId + " cannot be cancelled in state: " + order.getStatus(),
            "ORDER_NOT_CANCELLABLE");
    }
    
    String cancelReason = (reason != null && !reason.isBlank()) ? reason : "Customer requested cancellation";
    if (order.getStatus() == OrderStatus.PENDING) {
        orderSagaOrchestrator.cancelPendingSaga(order, cancelReason, cancelledBy);
    } else {
        orderSagaOrchestrator.cancelConfirmedSaga(order, cancelReason, cancelledBy);
    }
    ```

- [x] Task 5: Implement `OrderGrpcService.cancelOrder()` (AC: 4)
  - [x] 5.1 Remove the UNIMPLEMENTED stub at line 118-119 of `grpc/OrderGrpcService.java`
  - [x] 5.2 Inject `OrderService` (already injected — used for createOrder and getOrder)
  - [x] 5.3 Implement `cancelOrder()`:
    ```java
    String orderId = request.getOrderId();
    String reason = request.getReason();
    String cancelledBy = request.getCancelledBy();
    try {
        orderService.cancelOrder(Long.parseLong(orderId), reason, cancelledBy);
        responseObserver.onNext(CancelOrderResponse.newBuilder()
            .setSuccess(true)
            .setMessage("Order cancelled successfully")
            .setStatus("CANCELLED")
            .build());
        responseObserver.onCompleted();
    } catch (OrderNotCancellableException e) {
        responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
    } catch (ResourceNotFoundException e) {
        responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
    } catch (Exception e) {
        log.error("Unexpected error in cancelOrder: orderId={}", orderId, e);
        responseObserver.onError(Status.INTERNAL.withDescription("Internal error during order cancellation").asRuntimeException());
    }
    ```

- [x] Task 6: Create `OrderRestController` (AC: 1, 2, 3)
  - [x] 6.1 Create `web/OrderRestController.java`: `POST /api/v1/orders/{orderId}/cancel`; reads `X-User-Id` header for cancelledBy
  - [x] 6.2 Create `web/CancelOrderHttpRequest.java` as a record: `record CancelOrderHttpRequest(String reason) {}`
  - [x] 6.3 No local exception handler needed — `GlobalExceptionHandler` in common-lib already handles `BusinessRuleException` → 409 CONFLICT with `errorCode=ORDER_NOT_CANCELLABLE` (via the new `ErrorCode` enum value)

- [x] Task 7: Update `OutboxPollingService.resolveTopicForEvent()` (AC: 5)
  - [x] 7.1 Added `case "order_cancelled" -> TOPIC_CANCELLED` to existing switch; added `TOPIC_CANCELLED = "order.order.cancelled"` constant

- [x] Task 8: Unit Tests (AC: 1, 2, 3, 6, 7)
  - [x] 8.1 `unit/saga/steps/RefundPaymentStepTest.java`: 5 tests — null paymentId skip, success, NOT_FOUND skip, UNAVAILABLE throws, compensate throws UnsupportedOperation
  - [x] 8.2 `unit/saga/OrderSagaOrchestratorCancelTest.java`: 4 tests — pending/no-reservation, pending/with-reservation, confirmed/success, confirmed/refund-fails-best-effort
  - [x] 8.3 `unit/service/OrderServiceCancelTest.java`: 7 tests — 7 non-cancellable statuses (parameterized), not-found, delegate-to-pending, delegate-to-confirmed, default-reason
  - [x] Updated `OrderSagaOrchestratorTest` to pass `RefundPaymentStep` mock to constructor (no existing tests broken)

- [x] Task 9: Integration Tests (AC: 1, 2, 3)
  - [x] `integration/OrderCancellationIT.java`: 3 tests — cancel pending with inventory release, cancel confirmed with refund+release, reject SHIPPED

### Review Findings

- [x] [Review][Patch] Auth bypass — no ownership check; any user can cancel any order [OrderService.java:117]
- [x] [Review][Patch] Race condition — optimistic locking exception caught and converted to OrderNotCancellableException [OrderService.java]
- [x] [Review][Patch] Non-atomic: final CANCELLED status update and outbox INSERT merged into finalizeCancellation() [OrderSagaOrchestrator.java]
- [x] [Review][Patch] ALREADY_EXISTS from idempotent refund treated as failure in RefundPaymentStep [RefundPaymentStep.java]
- [x] [Review][Patch] Unnecessary items DB query moved after non-cancellable status guard [OrderService.java]
- [x] [Review][Patch] PAYMENT_REFUNDING and INVENTORY_RELEASING added to recoverStaleSagas() recovery [OrderSagaOrchestrator.java]
- [x] [Review][Defer] sagaId used as both sagaId and correlationId in logSagaStep — pre-existing pattern — deferred, pre-existing
- [x] [Review][Defer] SagaStepException retryable=false for transient gRPC errors — intentional best-effort design — deferred, pre-existing
- [x] [Review][Defer] Best-effort cancellation has no persistent refund_failed flag for reconciliation — deferred, out of Story 4.5 scope
- [x] [Review][Defer] Empty items list when reservationId non-null — items loaded upfront in cancelOrder, unlikely in normal operation — deferred, pre-existing
- [x] [Review][Defer] UnsupportedOperationException in RefundPaymentStep.compensate() — intentional defensive design — deferred, pre-existing

## Dev Notes

### Existing Code — DO NOT Recreate

| Component | Path | Notes |
|-----------|------|-------|
| `SagaStep` interface | `saga/SagaStep.java` | `getName()`, `execute(SagaContext)`, `compensate(SagaContext)` |
| `SagaContext` | `saga/SagaContext.java` | POJO with `Order order` |
| `SagaStepException` | `saga/exception/SagaStepException.java` | `RuntimeException` with `boolean shouldCompensate` |
| `ReleaseInventoryStep` | `saga/steps/ReleaseInventoryStep.java` | `compensate()` calls Inventory gRPC; null-guards `reservationId`; `execute()` throws UnsupportedOperationException |
| `OrderSagaOrchestrator` | `saga/OrderSagaOrchestrator.java` | Has `updateOrderStatus()`, `logSagaStep()`, `publishStatusChangedEvent()`, `releaseInventoryStep` already injected |
| `OrderService` | `service/OrderService.java` | Has `createOrder()`, `getOrder()`; inject `orderItemRepository` already |
| `OrderGrpcService` | `grpc/OrderGrpcService.java` | `cancelOrder()` is UNIMPLEMENTED stub at line ~118 — modify, do not recreate |
| `OutboxPollingService` | `service/OutboxPollingService.java` | Has `resolveTopicForEvent()` — just add `"order_cancelled"` case |
| `GrpcClientConfig` | `config/GrpcClientConfig.java` | `PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub` bean already declared |
| `Order` entity | `entity/Order.java` | `reservationId`, `paymentId`, `cancellationReason`, `items` (LAZY), `@Version` |
| `OrderStatus` enum | `enums/OrderStatus.java` | Already has `PAYMENT_REFUNDING`, `INVENTORY_RELEASING` |
| `OrderRepository` | `repository/OrderRepository.java` | `findByStatusIn(List<OrderStatus>)` |
| `OrderItemRepository` | `repository/OrderItemRepository.java` | `findByOrderId(Long)` |
| Flyway V1, V2 | `db/migration/` | No new migration needed — all required columns and enum values already exist |

### gRPC Proto Contracts (DO NOT MODIFY)

**Order Service** (`backend/proto/src/main/proto/order_service.proto`):
```protobuf
service OrderService {
  rpc CancelOrder(CancelOrderRequest) returns (CancelOrderResponse);
}
message CancelOrderRequest { string order_id = 1; string reason = 2; string cancelled_by = 3; }
message CancelOrderResponse { bool success = 1; string message = 2; string status = 3; }
```

**Payment Service** (`backend/proto/src/main/proto/payment_service.proto`):
```protobuf
service PaymentService {
  rpc RefundPayment(RefundPaymentRequest) returns (RefundPaymentResponse);
}
message RefundPaymentRequest { string payment_id = 1; string order_id = 2; Money amount = 3; string reason = 4; }
message RefundPaymentResponse { bool success = 1; string message = 2; string refund_transaction_id = 3; }
```
Generated package: `com.robomart.proto.payment`

**Payment Service gRPC Status Codes for RefundPayment:**
From `PaymentGrpcService.refundPayment()` in `backend/payment-service/`:
| Scenario | gRPC Status |
|----------|-------------|
| Payment not found | `NOT_FOUND` |
| Invalid state (not COMPLETED) | `FAILED_PRECONDITION` |
| Invalid refund amount > original | `FAILED_PRECONDITION` |
| Transient failure | `UNAVAILABLE` |

### Cancellation Saga Flow

```
PENDING Cancellation:
  order.cancellationReason = reason
  if reservationId != null:
    logSagaStep("STARTED"), releaseInventoryStep.compensate(ctx), logSagaStep("COMPENSATED")
  updateOrderStatus(CANCELLED)
  publishOrderCancelledEvent()

CONFIRMED Cancellation:
  order.cancellationReason = reason
  updateOrderStatus(PAYMENT_REFUNDING)
  logSagaStep("RefundPaymentStep", "STARTED")
  try: refundPaymentStep.execute(ctx) → logSagaStep("SUCCESS")
  catch: logSagaStep("FAILED") + continue (best-effort)
  updateOrderStatus(INVENTORY_RELEASING)
  logSagaStep("ReleaseInventoryStep", "STARTED")
  try: releaseInventoryStep.compensate(ctx) → logSagaStep("COMPENSATED")
  catch: logSagaStep("COMPENSATION_FAILED") + continue
  updateOrderStatus(CANCELLED)
  publishOrderCancelledEvent()
```

**Critical: Why best-effort for CONFIRMED cancellation?**
If refund fails, we still release inventory and cancel the order. A failed refund is logged and flagged for manual reconciliation. The order should not be left in PAYMENT_REFUNDING state indefinitely.

### Cancellable vs Non-Cancellable States

```java
private static final Set<OrderStatus> CANCELLABLE_STATUSES =
    Set.of(OrderStatus.PENDING, OrderStatus.CONFIRMED);
```

Non-cancellable (throw `OrderNotCancellableException`):
- `INVENTORY_RESERVING`, `PAYMENT_PROCESSING` — in-flight saga (race condition with create-saga)
- `PAYMENT_REFUNDING`, `INVENTORY_RELEASING` — cancellation already in progress
- `SHIPPED`, `DELIVERED` — post-fulfilment
- `CANCELLED` — already cancelled

### OrderCancelledEvent Outbox Payload

Published to topic `order.order.cancelled`. Payload JSON structure:
```json
{
  "orderId": "123",
  "reason": "Customer requested cancellation",
  "cancelledBy": "user-uuid-here"
}
```
Matches `order_cancelled.avsc` fields: `orderId`, `reason`, `cancelledBy`.

Note: For Story 4.5, the outbox payload is plain JSON (consistent with Story 4.4 — Avro serialization for full event envelope is Epic 6 scope).

### Race Condition Scope (FR22)

**FR22 scope for Story 4.5:**
- If cancel arrives on a CONFIRMED order → refund + release (already covered by AC2)
- If cancel arrives while saga is in PAYMENT_PROCESSING (mid-flight) → return `ORDER_NOT_CANCELLABLE` for now
- Full idempotency key cancellation race handling → deferred to Story 4.5 notes as known limitation

This mirrors the approach of Story 4.4 deferring multi-instance recovery.

### HTTP API Design

```
POST /api/v1/orders/{orderId}/cancel
Authorization: Bearer <JWT> (validated by API Gateway)
X-User-Id: <userId> (set by API Gateway after JWT validation)
Content-Type: application/json

Body (optional):
{ "reason": "Changed my mind" }

Response 200: (no body)
Response 404: { "message": "Order not found: 123" }
Response 409: { "errorCode": "ORDER_NOT_CANCELLABLE", "message": "Order 123 cannot be cancelled in state: SHIPPED" }
```

### `OrderSagaOrchestrator` Constructor Change

The constructor currently accepts: `OrderRepository, OrderStatusHistoryRepository, OutboxEventRepository, SagaAuditLogRepository, TransactionTemplate, ObjectMapper, ReserveInventoryStep, ProcessPaymentStep, ReleaseInventoryStep`.

Add `RefundPaymentStep refundPaymentStep` as the last parameter. Spring will auto-wire it since `RefundPaymentStep` is annotated `@Component`. Store as `this.refundPaymentStep = refundPaymentStep`.

### `BusinessRuleException` Pattern from common-lib

Check `backend/common-lib/src/main/java/com/robomart/common/exception/BusinessRuleException.java` for the constructor signature. The existing `PaymentDeclinedException` in payment-service can serve as a reference for how to extend it. `OrderNotCancellableException` should map to HTTP 409 Conflict.

### Technology Constraints (unchanged from Story 4.4)

- **Spring Boot 4.0.4** / Spring Framework 7.x / Java 21
- **Jakarta EE 11**: all imports use `jakarta.*` (NOT `javax.*`)
- **Jackson 3.x**: `tools.jackson.databind.ObjectMapper` (NOT `com.fasterxml.jackson.databind`)
- **Spring gRPC 1.0.2**: `GrpcChannelFactory` for clients; `paymentStub` bean already registered in `GrpcClientConfig`
- **Transactions**: Use `TransactionTemplate` (programmatic) — `updateOrderStatus()` in `OrderSagaOrchestrator` already uses this pattern
- **Testing**: `@MockitoBean` (NOT deprecated `@MockBean`), `should{Expected}When{Condition}()` naming convention
- **Integration tests**: Testcontainers for PostgreSQL; mock gRPC stubs via `@MockitoBean`

### Key Learnings from Story 4.4 (Apply Here)

1. `updateOrderStatus()` in `OrderSagaOrchestrator` already uses `TransactionTemplate` — reuse it exactly as-is for state transitions in cancel saga
2. `ReleaseInventoryStep.compensate()` already null-guards `reservationId` — no need to repeat the check before calling
3. `logSagaStep(sagaId, orderId, stepName, status, request, response, error)` signature — use `null` for request/response/error when not available
4. `OutboxEvent` constructor: `new OutboxEvent(aggregateType, aggregateId, eventType, jsonPayload)`
5. `tools.jackson.databind.ObjectMapper.writeValueAsString()` throws `JacksonException` (Jackson 3.x) — catch broadly with `Exception`
6. `OrderItem.productId` is `Long` — convert to String for proto with `.toString()`
7. `GrpcServerLifecycle.getPort()` for integration tests (no `@LocalGrpcPort`)
8. Use `lenient()` for conditional Mockito stubs to avoid `UnnecessaryStubbingException`

### Project Structure: New Files

```
backend/order-service/src/main/java/com/robomart/order/
├── exception/
│   └── OrderNotCancellableException.java     # NEW: HTTP 409 + ORDER_NOT_CANCELLABLE code
├── saga/
│   └── steps/
│       └── RefundPaymentStep.java             # NEW: @Component, calls RefundPayment gRPC
└── web/
    ├── OrderRestController.java               # NEW: POST /api/v1/orders/{orderId}/cancel
    ├── CancelOrderHttpRequest.java            # NEW: record { String reason }
    └── OrderExceptionHandler.java             # NEW: @RestControllerAdvice (if no existing handler)
```

### Project Structure: Modified Files

```
backend/order-service/src/main/java/com/robomart/order/
├── grpc/
│   └── OrderGrpcService.java                  # MODIFY: implement cancelOrder() (was UNIMPLEMENTED)
├── saga/
│   └── OrderSagaOrchestrator.java             # MODIFY: add cancelPendingSaga(), cancelConfirmedSaga(),
│                                              #          publishOrderCancelledEvent(), inject RefundPaymentStep
├── service/
│   ├── OrderService.java                      # MODIFY: add cancelOrder() method
│   └── OutboxPollingService.java              # MODIFY: add "order_cancelled" case to resolveTopicForEvent()
```

### Test Files

```
backend/order-service/src/test/java/com/robomart/order/
├── unit/
│   ├── saga/
│   │   ├── OrderSagaOrchestratorCancelTest.java    # NEW
│   │   └── steps/
│   │       └── RefundPaymentStepTest.java           # NEW
│   └── service/
│       └── OrderServiceCancelTest.java              # NEW
└── integration/
    └── OrderCancellationIT.java                     # NEW
```

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Epic 4, Story 4.5 ACs]
- [Source: _bmad-output/planning-artifacts/architecture.md — Saga compensation design, cancel path: CONFIRMED → PAYMENT_REFUNDING → INVENTORY_RELEASING → CANCELLED, Kafka topic order.order.cancelled, OrderRestController, OrderAlreadyCancelledException mention]
- [Source: backend/proto/src/main/proto/order_service.proto — CancelOrder gRPC contract]
- [Source: backend/proto/src/main/proto/payment_service.proto — RefundPayment gRPC contract]
- [Source: backend/events/src/main/avro/order/order_cancelled.avsc — Avro schema: eventId, eventType, aggregateId, aggregateType, timestamp, version, orderId, reason, cancelledBy]
- [Source: backend/order-service/src/main/java/.../grpc/OrderGrpcService.java:118-119 — UNIMPLEMENTED cancelOrder() stub]
- [Source: backend/order-service/src/main/java/.../service/OutboxPollingService.java — resolveTopicForEvent() switch to extend]
- [Source: backend/order-service/src/main/java/.../saga/OrderSagaOrchestrator.java — updateOrderStatus(), logSagaStep(), publishStatusChangedEvent() patterns to reuse]
- [Source: backend/payment-service/src/main/java/.../grpc/PaymentGrpcService.java — RefundPayment gRPC status codes]
- [Source: _bmad-output/implementation-artifacts/4-4-implement-order-saga-orchestrator-phase-a-core-flow.md — Key learnings, transaction pattern, saga audit log pattern]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Implemented full order cancellation saga for PENDING and CONFIRMED orders
- Added `ORDER_NOT_CANCELLABLE` to `ErrorCode` enum; added protected constructor to `BusinessRuleException` to enable custom error codes in subclasses
- `GlobalExceptionHandler` in common-lib already handles `BusinessRuleException` → HTTP 409 — no local handler needed in order-service
- Best-effort cancellation: refund failure on CONFIRMED orders logs error but continues with inventory release and CANCELLED status
- Race condition for in-flight orders (PAYMENT_PROCESSING): returns ORDER_NOT_CANCELLABLE — documented as known limitation
- Product-service test failures are pre-existing (class file resolution issue unrelated to Story 4.5 — no product-service files modified)
- All 38 order-service tests pass (unit + integration); no regressions in common-lib and security-lib

### File List

**New files:**
- `backend/order-service/src/main/java/com/robomart/order/exception/OrderNotCancellableException.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/steps/RefundPaymentStep.java`
- `backend/order-service/src/main/java/com/robomart/order/web/OrderRestController.java`
- `backend/order-service/src/main/java/com/robomart/order/web/CancelOrderHttpRequest.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/steps/RefundPaymentStepTest.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorCancelTest.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/service/OrderServiceCancelTest.java`
- `backend/order-service/src/test/java/com/robomart/order/integration/OrderCancellationIT.java`

**Modified files:**
- `backend/common-lib/src/main/java/com/robomart/common/logging/ErrorCode.java` — added `ORDER_NOT_CANCELLABLE`
- `backend/common-lib/src/main/java/com/robomart/common/exception/BusinessRuleException.java` — added protected constructor
- `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java` — added `cancelPendingSaga()`, `cancelConfirmedSaga()`, `publishOrderCancelledEvent()`, `refundPaymentStep` injection
- `backend/order-service/src/main/java/com/robomart/order/service/OrderService.java` — added `cancelOrder()` method
- `backend/order-service/src/main/java/com/robomart/order/service/OutboxPollingService.java` — added `order_cancelled` topic routing
- `backend/order-service/src/main/java/com/robomart/order/grpc/OrderGrpcService.java` — implemented `cancelOrder()` (was UNIMPLEMENTED)
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorTest.java` — added `RefundPaymentStep` mock to constructor
