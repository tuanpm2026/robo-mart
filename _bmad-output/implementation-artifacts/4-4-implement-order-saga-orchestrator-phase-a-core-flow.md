# Story 4.4: Implement Order Saga Orchestrator (Phase A — Core Flow)

Status: done

## Story

As a customer,
I want to place an order that coordinates inventory reservation and payment processing reliably,
So that my order either completes fully or rolls back cleanly with no inconsistent state.

## Acceptance Criteria

1. **Given** `OrderGrpcService.createOrder()` receives a valid request, **When** processed, **Then** an Order is created in PENDING state with OrderStatusHistory entry, the Saga executes steps sequentially (ReserveInventoryStep → ProcessPaymentStep), and the order progresses through states: PENDING → INVENTORY_RESERVING → PAYMENT_PROCESSING → CONFIRMED. Each transition recorded in `order_status_history`. (FR14, FR15, FR19)

2. **Given** the Saga happy path completes (both steps succeed), **When** order reaches CONFIRMED, **Then** an `order.order.status-changed` outbox event is published via Kafka (via OutboxPollingService), order `status=CONFIRMED`, `reservation_id` and `payment_id` persisted on Order entity. (FR15)

3. **Given** payment fails during Saga (ProcessPaymentStep returns failure), **When** `FAILED_PRECONDITION` gRPC status received, **Then** compensation executes: `ReleaseInventoryStep` calls Inventory gRPC to release reserved stock, order status changes to CANCELLED with `cancellation_reason="Payment declined"`, all steps logged in `saga_audit_log`. (FR16)

4. **Given** inventory reservation fails (out of stock), **When** `FAILED_PRECONDITION` gRPC status received from Inventory, **Then** Saga stops immediately — no payment attempted, order status changes to CANCELLED with `cancellation_reason="Insufficient stock"`. No compensation needed (nothing reserved). (FR15, FR16)

5. **Given** payment transient failure (`UNAVAILABLE` gRPC status), **When** ProcessPaymentStep encounters it, **Then** exponential backoff retry executes: max 3 attempts, initial delay 1 second, multiplier 2x. Uses orderId as idempotency key. Only after all retries exhausted does Saga compensate. (FR30)

6. **Given** Order Service restarts mid-saga, **When** orders found in INVENTORY_RESERVING or PAYMENT_PROCESSING states, **Then** Saga recovery executes: attempts to resume from last known state, or compensates if unable to resume. Uses `@EventListener(ApplicationReadyEvent.class)`. (FR15)

7. **Given** any Saga step executes, **When** step starts/completes/fails, **Then** `saga_audit_log` records: sagaId (orderId), orderId, stepName, status (STARTED/SUCCESS/FAILED/COMPENSATED), request summary, response summary, executedAt timestamp.

## Tasks / Subtasks

- [x] Task 1: Flyway migration + Order entity fields (AC: 2, 3, 6)
  - [x] 1.1 Create `V2__add_saga_fields_to_orders.sql`: `ALTER TABLE orders ADD COLUMN reservation_id VARCHAR(100); ALTER TABLE orders ADD COLUMN payment_id VARCHAR(100); ALTER TABLE orders ADD COLUMN cancellation_reason TEXT;`
  - [x] 1.2 Add fields to `Order.java` entity: `reservationId`, `paymentId`, `cancellationReason` with getters/setters

- [x] Task 2: Saga interfaces and context (AC: 1, 3)
  - [x] 2.1 Create `saga/SagaStep.java` interface: `String getName()`, `void execute(SagaContext ctx)`, `void compensate(SagaContext ctx)`
  - [x] 2.2 Create `saga/SagaContext.java` (plain POJO, NOT an entity): holds `Order order` reference — steps mutate `order.reservationId`, `order.paymentId`, `order.cancellationReason` directly on the Order entity

- [x] Task 3: Implement OrderSagaOrchestrator (AC: 1, 2, 3, 4, 5, 7)
  - [x] 3.1 Create `saga/OrderSagaOrchestrator.java` annotated with `@Service`
  - [x] 3.2 Inject: `OrderRepository`, `OrderStatusHistoryRepository`, `SagaAuditLogRepository`, `OutboxEventRepository`, `ObjectMapper`, list of forward steps, list of compensation steps
  - [x] 3.3 `executeSaga(Order order)`: update order to INVENTORY_RESERVING → execute ReserveInventoryStep → update to PAYMENT_PROCESSING → execute ProcessPaymentStep → update to CONFIRMED → publish outbox event. On any step failure: run compensation and update to CANCELLED.
  - [x] 3.4 `executeSteps(List<SagaStep> steps, SagaContext ctx)`: iterate steps, log to saga_audit_log before/after each, catch `SagaStepException` to trigger compensation
  - [x] 3.5 `updateOrderStatus(Order order, OrderStatus newStatus)`: sets order.status, saves Order, creates OrderStatusHistory record (status, changedAt=Instant.now()), saves via `OrderStatusHistoryRepository`. Uses `TransactionTemplate` — NOT `@Transactional` (consistent with Pattern from 4.2/4.3).
  - [x] 3.6 `publishStatusChangedEvent(Order order, OrderStatus previousStatus)`: creates OutboxEvent with aggregateType="Order", aggregateId=order.id.toString(), eventType="order_status_changed", payload=JSON with orderId, previousStatus, newStatus fields. Saves via `OutboxEventRepository`.
  - [x] 3.7 `logSagaStep(String sagaId, String orderId, String stepName, String status, String request, String response, String error)`: creates and saves `SagaAuditLog` entity.

- [x] Task 4: Implement Saga Steps (AC: 1, 3, 4, 5)
  - [x] 4.1 Create `saga/steps/ReserveInventoryStep.java`:
    - Inject `InventoryServiceGrpc.InventoryServiceBlockingStub`
    - `execute()`: build `ReserveInventoryRequest` from `order.getItems()` (map each item to `ReservationItem(productId, quantity)`), call stub, on success set `order.reservationId = response.reservationId`
    - On `StatusRuntimeException` with status `FAILED_PRECONDITION`: set `order.cancellationReason="Insufficient stock"`, throw `SagaStepException` with `compensate=false` (nothing to compensate)
    - On other exceptions: throw `SagaStepException` with `compensate=true`
    - `compensate()`: no-op (inventory not reserved if execute failed)
  - [x] 4.2 Create `saga/steps/ProcessPaymentStep.java`:
    - Inject `PaymentServiceGrpc.PaymentServiceBlockingStub`
    - `execute()`: build `ProcessPaymentRequest(orderId, userId, Money(amount), idempotencyKey=orderId)`, call stub with retry (max 3, 1s initial, 2x backoff)
    - On success: set `order.paymentId = response.paymentId`
    - On `FAILED_PRECONDITION`: permanent failure, set `order.cancellationReason="Payment declined"`, throw `SagaStepException(compensate=true)` — triggers inventory compensation
    - On `UNAVAILABLE`: retry with backoff; after max retries, set `order.cancellationReason="Payment service unavailable"`, throw `SagaStepException(compensate=true)`
    - `compensate()`: no-op (Payment Service handles idempotency; no refund needed since payment not confirmed)
  - [x] 4.3 Create `saga/steps/ReleaseInventoryStep.java`:
    - Inject `InventoryServiceGrpc.InventoryServiceBlockingStub`
    - `compensate()`: build `ReleaseInventoryRequest(orderId, reservationId, items)`, call stub. If `order.reservationId` is null, skip (nothing to release).
    - `execute()`: throw `UnsupportedOperationException` (compensation-only step)
  - [x] 4.4 Create `saga/exception/SagaStepException.java`: `RuntimeException` with field `boolean shouldCompensate`

- [x] Task 5: gRPC Client Configuration (AC: 1, 3, 5)
  - [x] 5.1 Create `config/GrpcClientConfig.java`:
    ```java
    @Configuration
    public class GrpcClientConfig {
        @Bean
        public InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub(GrpcChannelFactory factory) {
            return InventoryServiceGrpc.newBlockingStub(factory.createChannel("inventory-service"));
        }
        @Bean
        public PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub(GrpcChannelFactory factory) {
            return PaymentServiceGrpc.newBlockingStub(factory.createChannel("payment-service"));
        }
    }
    ```
  - [x] 5.2 Add gRPC client config to `application.yml` (dev profile):
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
    Note: Inventory Service gRPC port is **9094** (from `backend/inventory-service/src/main/resources/application.yml`)

- [x] Task 6: Kafka Producer + OutboxPollingService (AC: 2)
  - [x] 6.1 Add Kafka producer config to `application.yml`:
    ```yaml
    spring:
      kafka:
        producer:
          key-serializer: org.apache.kafka.common.serialization.StringSerializer
          value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
    ```
  - [x] 6.2 Create `service/OutboxPollingService.java`:
    - `@Service`, use `@Scheduled(fixedDelay = 1000)`, inject `OutboxEventRepository`, `KafkaTemplate<String, String>`
    - Method `pollAndPublish()`: query `outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc()`, take first 50, for each publish to topic `"order.order.status-changed"` with key=aggregateId, then call `event.markPublished()`, save
    - Wrap each publish in try/catch — log failures but continue processing other events
    - Enable scheduling: add `@EnableScheduling` to `OrderServiceApplication.java` or a config class

- [x] Task 7: OrderService (AC: 1, 2)
  - [x] 7.1 Create `service/OrderService.java` with `@Service`
  - [x] 7.2 Inject: `OrderRepository`, `OrderStatusHistoryRepository`, `OutboxEventRepository`, `OrderSagaOrchestrator`, `TransactionTemplate`, `ObjectMapper`
  - [x] 7.3 `createOrder(CreateOrderRequest request)`:
    - Use `TransactionTemplate` to create Order + OrderItems + initial OrderStatusHistory(PENDING) atomically
    - After transaction commits, call `orderSagaOrchestrator.executeSaga(order)` (outside transaction — Saga manages its own state updates)
    - Return order
  - [x] 7.4 `getOrder(Long orderId)`: query `orderRepository.findById()`, throw `ResourceNotFoundException` if not found

- [x] Task 8: Implement OrderGrpcService (AC: 1, 2)
  - [x] 8.1 Modify `grpc/OrderGrpcService.java` — inject `OrderService`
  - [x] 8.2 Implement `createOrder()`: parse `CreateOrderRequest` proto → call `orderService.createOrder()` → return `CreateOrderResponse(success, orderId, status)`
  - [x] 8.3 Implement `getOrder()`: call `orderService.getOrder(orderId)` → map to `GetOrderResponse`
  - [x] 8.4 Add exception mapping: `ResourceNotFoundException` → `NOT_FOUND`, `SagaStepException` → `INTERNAL`, other exceptions → `INTERNAL`
  - [x] 8.5 `cancelOrder()`: leave as UNIMPLEMENTED (Story 4.5 scope)

- [x] Task 9: Saga recovery on startup (AC: 6)
  - [x] 9.1 In `OrderSagaOrchestrator`, add `@EventListener(ApplicationReadyEvent.class)` method `recoverStaleSagas()`
  - [x] 9.2 Query orders in INVENTORY_RESERVING or PAYMENT_PROCESSING states
  - [x] 9.3 For each stale order: re-run saga from current state. On failure, compensate and cancel.
  - [x] 9.4 Log recovery action to saga_audit_log with stepName="RECOVERY"

- [x] Task 10: Unit tests (AC: 1, 3, 4, 5)
  - [x] 10.1 `unit/saga/OrderSagaOrchestratorTest.java`:
    - Test happy path: both steps succeed → order CONFIRMED, saga_audit_log has entries
    - Test inventory failure: order CANCELLED, no payment attempted, cancellationReason set
    - Test payment declined: order CANCELLED, ReleaseInventoryStep executed
    - Test payment transient + retry: verify 3 attempts, then compensation
    - Test payment transient + eventually succeeds on 2nd attempt: order CONFIRMED
  - [x] 10.2 `unit/saga/steps/ReserveInventoryStepTest.java`: test success, FAILED_PRECONDITION, other gRPC error
  - [x] 10.3 `unit/saga/steps/ProcessPaymentStepTest.java`: test success, FAILED_PRECONDITION (declined), UNAVAILABLE (retry), UNAVAILABLE exhausted

- [x] Task 11: Integration tests (AC: 1, 2, 3, 4)
  - [x] 11.1 `integration/OrderSagaIT.java`:
    - Use Testcontainers PostgreSQL
    - Mock gRPC stubs (mock the `BlockingStub` beans with `@MockitoBean`)
    - Test happy path: full Saga flow → order CONFIRMED, outbox event created
    - Test inventory failure: order CANCELLED, no payment call
    - Test payment failure: order CANCELLED, release inventory called
    - Verify `saga_audit_log` entries for each test

## Dev Notes

### Existing Code — DO NOT Recreate

All infrastructure was created in Story 4.1. These files already exist:

| Component | Path | Notes |
|-----------|------|-------|
| `Order` entity | `entity/Order.java` | Has `@Version`, userId, totalAmount, status (OrderStatus enum), shippingAddress — **needs 3 new fields in Task 1** |
| `OrderItem` entity | `entity/OrderItem.java` | Has productId (Long), productName, quantity, unitPrice, subtotal |
| `OrderStatusHistory` entity | `entity/OrderStatusHistory.java` | Has order (FK), status (enum), changedAt (Instant) |
| `OutboxEvent` entity | `entity/OutboxEvent.java` | Same pattern as Payment/Inventory — constructor: `(aggregateType, aggregateId, eventType, payload)`, `markPublished()` |
| `SagaAuditLog` entity | `entity/SagaAuditLog.java` | Has sagaId, orderId, stepName, status, request, response, error, executedAt |
| `OrderStatus` enum | `enums/OrderStatus.java` | Values: PENDING, INVENTORY_RESERVING, PAYMENT_PROCESSING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, PAYMENT_REFUNDING, INVENTORY_RELEASING |
| `OrderRepository` | `repository/OrderRepository.java` | `findByUserId(String, Pageable)` |
| `OrderStatusHistoryRepository` | `repository/OrderStatusHistoryRepository.java` | Exists |
| `OutboxEventRepository` | `repository/OutboxEventRepository.java` | `findByPublishedFalseOrderByCreatedAtAsc()` |
| `SagaAuditLogRepository` | `repository/SagaAuditLogRepository.java` | `findBySagaIdOrderByExecutedAtAsc()`, `findByOrderIdOrderByExecutedAtAsc()` |
| `OrderGrpcService` | `grpc/OrderGrpcService.java` | Stub with UNIMPLEMENTED — **modify, do not recreate** |
| `OrderServiceApplication` | `OrderServiceApplication.java` | `scanBasePackages = "com.robomart"` |
| `application.yml` | `resources/application.yml` | gRPC server on 9093, HTTP on 8083, DB on 5434, Kafka bootstrap at 29092 |
| `V1__init_order_schema.sql` | `db/migration/V1__init_order_schema.sql` | Creates: orders, order_items, order_status_history, outbox_events, saga_audit_log |

### gRPC Proto Contracts (DO NOT MODIFY)

**Inventory Service** (`backend/proto/src/main/proto/inventory_service.proto`):
```protobuf
service InventoryService {
  rpc ReserveInventory(ReserveInventoryRequest) returns (ReserveInventoryResponse);
  rpc ReleaseInventory(ReleaseInventoryRequest) returns (ReleaseInventoryResponse);
}
message ReserveInventoryRequest { string order_id = 1; repeated ReservationItem items = 2; }
message ReservationItem { string product_id = 1; int32 quantity = 2; }
message ReserveInventoryResponse { bool success = 1; string message = 2; string reservation_id = 3; }
message ReleaseInventoryRequest { string order_id = 1; string reservation_id = 2; repeated ReservationItem items = 3; }
message ReleaseInventoryResponse { bool success = 1; string message = 2; }
```
Generated package: `com.robomart.proto.inventory`

**Payment Service** (`backend/proto/src/main/proto/payment_service.proto`):
```protobuf
service PaymentService {
  rpc ProcessPayment(ProcessPaymentRequest) returns (ProcessPaymentResponse);
}
message ProcessPaymentRequest { string order_id = 1; string user_id = 2; Money amount = 3; string idempotency_key = 4; }
message ProcessPaymentResponse { bool success = 1; string message = 2; string payment_id = 3; string transaction_id = 4; }
```
Generated package: `com.robomart.proto.payment`

**Common types** (`common/types.proto`):
```protobuf
message Money { string currency = 1; string amount = 2; }
message Address { string street = 1; string city = 2; string state = 3; string zip = 4; string country = 5; }
```

**Order Service** (`backend/proto/src/main/proto/order_service.proto`):
```protobuf
message CreateOrderRequest { string user_id = 1; repeated OrderItemRequest items = 2; Address shipping_address = 3; string idempotency_key = 4; }
message OrderItemRequest { string product_id = 1; string product_name = 2; int32 quantity = 3; Money unit_price = 4; }
message CreateOrderResponse { bool success = 1; string message = 2; string order_id = 3; string status = 4; }
```

### Service Ports (Critical for gRPC Client Config)

| Service | HTTP Port | gRPC Port |
|---------|-----------|-----------|
| Order Service | 8083 | 9093 |
| Inventory Service | 8084 | **9094** |
| Payment Service | 8086 | **9095** |

Inventory Service is at port **9094**, NOT 9092. Confirmed from `backend/inventory-service/target/classes/application.yml`.

### Spring gRPC 1.0.2 Client Pattern

```yaml
# application.yml (under dev profile or root)
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

```java
@Configuration
public class GrpcClientConfig {
    @Bean
    public InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub(
            GrpcChannelFactory channelFactory) {
        return InventoryServiceGrpc.newBlockingStub(channelFactory.createChannel("inventory-service"));
    }

    @Bean
    public PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub(
            GrpcChannelFactory channelFactory) {
        return PaymentServiceGrpc.newBlockingStub(channelFactory.createChannel("payment-service"));
    }
}
```
`GrpcChannelFactory` from `org.springframework.grpc.client`.

### Saga Orchestration Design

```
executeSaga(order):
  sagaId = order.getId().toString()
  SagaContext ctx = new SagaContext(order)

  Forward steps: [ReserveInventoryStep, ProcessPaymentStep]
  Compensation steps (reverse): [ReleaseInventoryStep]

  for each step in forward steps:
    log(sagaId, orderId, step.getName(), "STARTED", ...)
    updateOrderStatus(order, nextState)  // INVENTORY_RESERVING or PAYMENT_PROCESSING
    try:
      step.execute(ctx)
      log(sagaId, orderId, step.getName(), "SUCCESS", ...)
    catch SagaStepException e:
      log(sagaId, orderId, step.getName(), "FAILED", ..., e.message)
      if e.shouldCompensate:
        runCompensation(ctx)
      updateOrderStatus(order, CANCELLED)
      publishStatusChangedEvent(order, previousStatus)
      return

  // All steps succeeded
  updateOrderStatus(order, CONFIRMED)
  publishStatusChangedEvent(order, PAYMENT_PROCESSING)

runCompensation(ctx):
  for step in compensationSteps:
    try:
      log(sagaId, orderId, step.getName(), "COMPENSATING", ...)
      step.compensate(ctx)
      log(sagaId, orderId, step.getName(), "COMPENSATED", ...)
    catch Exception e:
      log(sagaId, orderId, step.getName(), "COMPENSATION_FAILED", ..., e.message)
      // Log but continue — best-effort compensation
```

### Retry Pattern for ProcessPaymentStep

```java
private static final int MAX_RETRIES = 3;
private static final long INITIAL_DELAY_MS = 1000L;
private static final double BACKOFF_MULTIPLIER = 2.0;

private ProcessPaymentResponse callWithRetry(ProcessPaymentRequest request) {
    long delay = INITIAL_DELAY_MS;
    StatusRuntimeException lastException = null;
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        try {
            return paymentStub.processPayment(request);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(delay);
                    delay = (long)(delay * BACKOFF_MULTIPLIER);
                }
            } else {
                throw e; // FAILED_PRECONDITION or other — don't retry
            }
        }
    }
    throw lastException; // All retries exhausted
}
```

### OutboxEvent Creation Pattern (consistent with Inventory/Payment Services)

```java
// Use tools.jackson.databind.ObjectMapper (Jackson 3.x — auto-configured by Spring Boot 4)
Map<String, Object> payload = new HashMap<>();
payload.put("orderId", order.getId().toString());
payload.put("previousStatus", previousStatus.name());
payload.put("newStatus", order.getStatus().name());

OutboxEvent event = new OutboxEvent(
    "Order",                          // aggregateType
    order.getId().toString(),          // aggregateId
    "order_status_changed",            // eventType
    objectMapper.writeValueAsString(payload)  // payload JSON
);
outboxEventRepository.save(event);
```

### TransactionTemplate Pattern (from Stories 4.2 and 4.3)

```java
// CORRECT: TransactionTemplate for explicit transaction boundaries
private final TransactionTemplate transactionTemplate;

public Order createOrderTransactionally(CreateOrderRequest request) {
    return transactionTemplate.execute(status -> {
        Order order = new Order();
        order.setUserId(request.getUserId());
        // ... set fields
        Order saved = orderRepository.save(order);
        // create OrderItems, initial OrderStatusHistory(PENDING)
        return saved;
    });
}
// After transaction commits, THEN call saga (outside transaction)
```

### gRPC Status Code Mapping

From Inventory Service (Story 4.2):
| Scenario | gRPC Status |
|----------|-------------|
| Insufficient stock | `FAILED_PRECONDITION` |
| Lock acquisition failure | `RESOURCE_EXHAUSTED` |

From Payment Service (Story 4.3):
| Scenario | gRPC Status |
|----------|-------------|
| Payment declined | `FAILED_PRECONDITION` |
| Transient failure | `UNAVAILABLE` → retry |
| Payment not found | `NOT_FOUND` |
| Bad request | `INVALID_ARGUMENT` |

### OrderItem→ReservationItem Mapping

`OrderItem.productId` is a `Long` — must convert to String for proto:
```java
.addItems(ReservationItem.newBuilder()
    .setProductId(item.getProductId().toString())
    .setQuantity(item.getQuantity())
    .build())
```

### Money proto construction

```java
Money amount = Money.newBuilder()
    .setCurrency("USD")
    .setAmount(order.getTotalAmount().toPlainString())
    .build();
```

### OrderStatusHistory creation

```java
OrderStatusHistory history = new OrderStatusHistory();
history.setOrder(order);
history.setStatus(newStatus);
history.setChangedAt(Instant.now());
orderStatusHistoryRepository.save(history);
```

### Project Structure Notes

New files to create (all under `backend/order-service/src/main/java/com/robomart/order/`):

```
config/
└── GrpcClientConfig.java              # gRPC BlockingStub beans for inventory + payment
saga/
├── SagaStep.java                      # Interface: getName(), execute(SagaContext), compensate(SagaContext)
├── SagaContext.java                   # POJO carrying Order reference
├── OrderSagaOrchestrator.java         # Core orchestration + recovery
├── exception/
│   └── SagaStepException.java         # RuntimeException with shouldCompensate flag
└── steps/
    ├── ReserveInventoryStep.java
    ├── ProcessPaymentStep.java
    └── ReleaseInventoryStep.java
service/
├── OrderService.java                  # createOrder(), getOrder()
└── OutboxPollingService.java          # @Scheduled(fixedDelay=1000), batch 50
```

New migration file:
```
db/migration/
└── V2__add_saga_fields_to_orders.sql
```

Modify existing:
```
entity/Order.java                      # Add reservationId, paymentId, cancellationReason
grpc/OrderGrpcService.java             # Implement createOrder(), getOrder()
resources/application.yml             # Add gRPC client + Kafka producer config
```

Test files (`backend/order-service/src/test/java/com/robomart/order/`):
```
unit/
├── saga/
│   ├── OrderSagaOrchestratorTest.java
│   └── steps/
│       ├── ReserveInventoryStepTest.java
│       └── ProcessPaymentStepTest.java
integration/
└── OrderSagaIT.java
```

### Technology Constraints

- **Spring Boot 4.0.4** / Spring Framework 7.x / Java 21
- **Jakarta EE 11**: all imports use `jakarta.*` (NOT `javax.*`)
- **Jackson 3.x**: `tools.jackson.databind.ObjectMapper` (NOT `com.fasterxml.jackson.databind`) — auto-configured by Spring Boot 4
- **Annotations**: `com.fasterxml.jackson.annotation.*` (unchanged)
- **Spring gRPC 1.0.2**: `@GrpcService` for server, `GrpcChannelFactory` for clients
- **Transactions**: Use `TransactionTemplate` (programmatic) — proven pattern from Stories 4.2 and 4.3
- **Testing**: `@MockitoBean` (NOT deprecated `@MockBean`), `should{Expected}When{Condition}()` naming
- **Integration tests**: Use Testcontainers for PostgreSQL; mock gRPC stubs via `@MockitoBean`
- **Exception hierarchy**: extend from `common-lib` (`ExternalServiceException`, `ResourceNotFoundException`, `BusinessRuleException`)
- **No `@LocalGrpcPort`**: use `GrpcServerLifecycle.getPort()` for integration tests (Spring gRPC 1.0.2)

### Key Learnings from Previous Stories (4.2 + 4.3)

1. Use `TransactionTemplate` instead of `@Transactional` when mixing DB operations with external calls
2. `tools.jackson.databind.ObjectMapper` for Jackson 3.x — Spring Boot 4 auto-configures it
3. `GrpcServerLifecycle.getPort()` for gRPC port discovery in integration tests (no `@LocalGrpcPort`)
4. Use `lenient()` for conditional Mockito stubs to avoid `UnnecessaryStubbingException`
5. `OutboxEvent` constructor: `new OutboxEvent(aggregateType, aggregateId, eventType, jsonPayload)`
6. Check for `DataIntegrityViolationException` on concurrent DB operations
7. Payment Service signals: `UNAVAILABLE` = transient (retry), `FAILED_PRECONDITION` = permanent (compensate)
8. Inventory Service signals: `FAILED_PRECONDITION` = insufficient stock (no compensation needed)

### Avro Event Schema

`order_status_changed.avsc` (topic: `order.order.status-changed`):
```json
{"name": "OrderStatusChangedEvent", "namespace": "com.robomart.events.order",
 "fields": ["eventId", "eventType", "aggregateId", "aggregateType", "timestamp",
             "version", "orderId", "previousStatus", "newStatus"]}
```
Note: OutboxPollingService publishes the JSON payload from OutboxEvent directly (not Avro-encoded) for Story 4.4. Full Avro serialization for Notification Service integration is Epic 6 scope.

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Epic 4, Story 4.4]
- [Source: _bmad-output/planning-artifacts/architecture.md — Saga Orchestration Design, Order Service directory structure, gRPC contracts table, Kafka topic mapping]
- [Source: _bmad-output/planning-artifacts/prd.md — FR14, FR15, FR16, FR19, FR30, NFR3, NFR44]
- [Source: backend/proto/src/main/proto/inventory_service.proto — Inventory gRPC contract]
- [Source: backend/proto/src/main/proto/payment_service.proto — Payment gRPC contract]
- [Source: backend/proto/src/main/proto/order_service.proto — Order gRPC contract]
- [Source: backend/inventory-service/target/classes/application.yml — Inventory Service gRPC port 9094]
- [Source: backend/order-service/src/main/java/.../entity/ — All existing entities]
- [Source: backend/order-service/src/main/resources/db/migration/V1__init_order_schema.sql — DB schema]
- [Source: backend/events/src/main/avro/order/ — Avro event schemas]
- [Source: _bmad-output/implementation-artifacts/4-3-implement-payment-service-with-idempotency-retry.md — TransactionTemplate pattern, Jackson 3.x, gRPC test patterns]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

### Review Findings

- [x] [Review][Defer] Orphaned payment in PAYMENT_PROCESSING crash recovery — deferred to Story 4.5: Phase A known limitation. Recovery cancels order without refund when crash occurs after payment succeeds but before CONFIRMED. Full cancel-with-compensation saga (including payment refund) is Story 4.5 scope.

- [x] [Review][Patch] createOrder() order stuck in PENDING on unexpected exception [OrderService.java:80] — If executeSaga() throws an unexpected RuntimeException (e.g., DataAccessException on item load), the order is committed to DB in PENDING state but the saga never runs. No compensation occurs and the order hangs indefinitely.

- [x] [Review][Patch] OutboxPollingService partial publish — Kafka send and DB mark-published are in separate transactions [OutboxPollingService.java:49] — If crash occurs after kafkaTemplate.send() succeeds but before transactionTemplate marks published, the event will be resent on next poll (duplicate). If send() throws synchronously, the catch swallows it and continues; the event is never retried in this batch but will be on next poll cycle (acceptable). Bigger risk: kafkaTemplate.send() is async (returns ListenableFuture) — add .get() or use a Kafka transaction to guarantee at-least-once delivery.

- [x] [Review][Patch] Recovery publishes incorrect previousStatus [OrderSagaOrchestrator.java:188] — publishStatusChangedEvent(order, OrderStatus.CANCELLED) passes CANCELLED as both previousStatus and newStatus. Should capture order.getStatus() before calling updateOrderStatus(order, CANCELLED) and pass that captured value.

- [x] [Review][Patch] ReleaseInventoryStep.compensate() may send empty items list during recovery [ReleaseInventoryStep.java:51] — Orders loaded by findByStatusIn() have lazy-loaded items collection. During recoverStaleSagas(), items are not eagerly fetched → ReleaseInventoryRequest built with empty items list. Fix: add @EntityGraph or JOIN FETCH on findByStatusIn() query.

- [x] [Review][Patch] getOrder() returns detached entity with lazy items → LazyInitializationException [OrderGrpcService.java:89] — OrderService.getOrder() fetches Order without a transaction context. OrderGrpcService then iterates order.getItems() outside any session → LazyInitializationException → INTERNAL gRPC error. Fix: either JOIN FETCH items in getOrder() or wrap in a transactionTemplate.

- [x] [Review][Defer] Multiple service instances recovering same stale orders (no distributed locking) [OrderSagaOrchestrator.java:167] — deferred, pre-existing: requires distributed locking (Redis/DB advisory lock); out of Phase A scope
- [x] [Review][Defer] recoverStaleSagas() blocks startup synchronously for large volumes [OrderSagaOrchestrator.java:167] — deferred, pre-existing: async recovery with bounded concurrency; out of Phase A scope
- [x] [Review][Defer] No gRPC deadlines/timeouts configured [application.yml:20-25] — deferred, pre-existing: production timeout config; out of Phase A scope
- [x] [Review][Defer] Hardcoded currency "USD" in ProcessPaymentStep [ProcessPaymentStep.java:48] — deferred, pre-existing: multi-currency support is future scope
- [x] [Review][Defer] Order.setVersion() public accessor technically exposes version manipulation [Order.java:115] — deferred, pre-existing: needed for saveAndFlush version sync pattern; low risk in current codebase

### File List
