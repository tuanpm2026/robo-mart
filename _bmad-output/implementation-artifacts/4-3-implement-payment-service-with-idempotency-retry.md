# Story 4.3: Implement Payment Service with Idempotency & Retry

Status: done

## Story

As a system,
I want the Payment Service to process mock payments with idempotency guarantees and retry support,
So that payments are never duplicated and temporary failures are handled gracefully.

## Acceptance Criteria

1. **Given** a ProcessPayment gRPC call with an idempotency key, **When** the payment is processed for the first time, **Then** MockPaymentGateway simulates payment processing, a Payment record is created (status=COMPLETED), the idempotency key is stored with 24-hour TTL, and a `payment_processed` outbox event is created (FR28, FR29, NFR45)

2. **Given** a duplicate ProcessPayment call with the same idempotency key, **When** received within 24 hours, **Then** the original payment result is returned without reprocessing — no duplicate charge (FR29)

3. **Given** a payment that fails transiently, **When** retry is triggered, **Then** exponential backoff is applied: max 3 retries, initial delay 1 second, backoff multiplier 2x (FR30)

4. **Given** a RefundPayment gRPC call, **When** received for a completed payment, **Then** the payment is refunded via MockPaymentGateway, payment status updated to REFUNDED, and a `payment_refunded` outbox event is created (FR31)

5. **Given** Payment Service is unavailable, **When** the gRPC call times out, **Then** the caller receives an UNAVAILABLE gRPC status, and the order should be held in PAYMENT_PENDING state (FR32)

## Tasks / Subtasks

- [x] Task 1: Implement MockPaymentGateway (AC: 1, 3)
  - [x] 1.1 Create `MockPaymentGateway` in `service/` — simulates payment processing with configurable success/failure
  - [x] 1.2 Support transient failure simulation (for testing retry)
  - [x] 1.3 Generate unique `transactionId` on success
  - [x] 1.4 Simulate refund processing

- [x] Task 2: Implement PaymentService business logic (AC: 1, 2, 4)
  - [x] 2.1 Create `PaymentService` in `service/` with `processPayment()` method
  - [x] 2.2 Implement idempotency check: query `IdempotencyKeyRepository.findByIdempotencyKey()` — if found and not expired, return cached response
  - [x] 2.3 If new: call MockPaymentGateway, create Payment entity (COMPLETED), create IdempotencyKey (expiresAt = now + 24h with cached response), create OutboxEvent — all in single transaction
  - [x] 2.4 Implement `refundPayment()` method: validate payment exists and status=COMPLETED, call MockPaymentGateway.refund(), update status to REFUNDED, create outbox event
  - [x] 2.5 Handle payment failure: set status to FAILED, do NOT store idempotency key (allow retry)

- [x] Task 3: Implement retry logic (AC: 3)
  - [x] 3.1 Retry logic lives in the **caller** (Order Saga in Story 4.4), but PaymentService must correctly distinguish transient vs permanent failures
  - [x] 3.2 MockPaymentGateway throws `PaymentTransientException` for retryable errors and `PaymentDeclinedException` for permanent failures
  - [x] 3.3 PaymentGrpcService maps transient → gRPC `UNAVAILABLE` (caller retries), permanent → gRPC `FAILED_PRECONDITION` (caller compensates)

- [x] Task 4: Implement PaymentGrpcService (AC: 1, 2, 4, 5)
  - [x] 4.1 Replace UNIMPLEMENTED stubs with real logic delegating to PaymentService
  - [x] 4.2 Map proto request/response to domain objects
  - [x] 4.3 Map exceptions to gRPC status codes (see error mapping table)

- [x] Task 5: Create exception classes (AC: 3, 5)
  - [x] 5.1 Create `PaymentDeclinedException extends ExternalServiceException` — permanent payment failure
  - [x] 5.2 Create `PaymentTransientException extends ExternalServiceException` — retryable failure

- [x] Task 6: Write unit tests (AC: 1, 2, 3, 4)
  - [x] 6.1 `unit/service/MockPaymentGatewayTest.java` — success, transient failure, permanent failure, refund
  - [x] 6.2 `unit/service/PaymentServiceTest.java` — process payment, idempotency dedup, refund, failure handling
  - [x] 6.3 `unit/grpc/PaymentGrpcServiceTest.java` — gRPC status mapping, request/response conversion

- [x] Task 7: Write integration tests (AC: 1, 2, 4)
  - [x] 7.1 `integration/PaymentServiceIT.java` — end-to-end: process payment → verify DB records + outbox event
  - [x] 7.2 Idempotency test: same key → same result, no duplicate
  - [x] 7.3 Refund test: completed payment → refund → verify status + outbox
  - [x] 7.4 `integration/PaymentGrpcIT.java` — full gRPC round-trip tests

### Review Findings

- [x] [Review][Patch] **Retry after transient failure blocked by unique constraint** — FAILED payment saved with idempotencyKey blocks retry (unique constraint on payments table). Integration test masks this by manually deleting FAILED payment. Fix: check for existing FAILED payment before creating new one, and reuse/update it. [PaymentService.java:80-90]
- [x] [Review][Patch] **Race condition on concurrent idempotency check (TOCTOU)** — Two concurrent requests can both pass idempotencyKey check and attempt gateway call. DB unique constraint prevents double-save but returns poor INTERNAL error. Fix: catch DataIntegrityViolationException and return existing result. [PaymentService.java:63-90]
- [x] [Review][Patch] **Expired idempotency key deletion not atomic** — Delete of expired key + payment happens outside transaction. Fix: wrap in TransactionTemplate. [PaymentService.java:72-76]
- [x] [Review][Patch] **No input validation for amount and currency** — Negative amounts, zero amounts, invalid currencies accepted. Fix: validate amount > 0 and currency non-blank in PaymentService. [PaymentService.java:57-60]
- [x] [Review][Patch] **Refund amount not validated against original payment** — Client can request refund exceeding original payment amount. Fix: add amount <= payment.getAmount() check. [PaymentService.java:152-165]
- [x] [Review][Defer] **Clock skew on idempotency TTL check** — Instant.now() for TTL check susceptible to clock skew across replicas. Use database CURRENT_TIMESTAMP for production. [PaymentService.java:66] — deferred, architectural decision for production deployment

## Dev Notes

### Existing Code — DO NOT Recreate

All infrastructure was created in Story 4.1. These files already exist:

| Component | Path | Notes |
|-----------|------|-------|
| `Payment` entity | `entity/Payment.java` | Has `@Version`, orderId (String), amount (BigDecimal), currency, status (PaymentStatus enum), transactionId, idempotencyKey |
| `IdempotencyKey` entity | `entity/IdempotencyKey.java` | Has idempotencyKey (unique), orderId, response (TEXT), expiresAt (Instant) |
| `OutboxEvent` entity | `entity/OutboxEvent.java` | Has `@JdbcTypeCode(SqlTypes.JSON)`, parameterized constructor `(aggregateType, aggregateId, eventType, payload)`, `markPublished()` |
| `PaymentStatus` enum | `enums/PaymentStatus.java` | Values: `PENDING, PROCESSING, COMPLETED, FAILED, REFUNDED` |
| `PaymentRepository` | `repository/PaymentRepository.java` | `findByOrderId(String)`, `findByIdempotencyKey(String)` |
| `IdempotencyKeyRepository` | `repository/IdempotencyKeyRepository.java` | `findByIdempotencyKey(String)` |
| `OutboxEventRepository` | `repository/OutboxEventRepository.java` | `findByPublishedFalse()` |
| `PaymentGrpcService` | `grpc/PaymentGrpcService.java` | Stub returning UNIMPLEMENTED — **modify, do not recreate** |
| `PaymentServiceApplication` | `PaymentServiceApplication.java` | Spring Boot main class |
| `application.yml` | `resources/application.yml` | gRPC on 9095, HTTP on 8086, DB on 5436 |
| Flyway migrations | `resources/db/migration/V1__init_payment_schema.sql` | Tables: `payments`, `idempotency_keys`, `outbox_events` |

### gRPC Proto Contract (DO NOT MODIFY)

File: `backend/proto/src/main/proto/payment_service.proto`

```protobuf
service PaymentService {
  rpc ProcessPayment(ProcessPaymentRequest) returns (ProcessPaymentResponse);
  rpc RefundPayment(RefundPaymentRequest) returns (RefundPaymentResponse);
}

message ProcessPaymentRequest {
  string order_id = 1;
  string user_id = 2;
  com.robomart.proto.common.Money amount = 3;
  string idempotency_key = 4;
}

message ProcessPaymentResponse {
  bool success = 1;
  string message = 2;
  string payment_id = 3;
  string transaction_id = 4;
}

message RefundPaymentRequest {
  string payment_id = 1;
  string order_id = 2;
  com.robomart.proto.common.Money amount = 3;
  string reason = 4;
}

message RefundPaymentResponse {
  bool success = 1;
  string message = 2;
  string refund_transaction_id = 3;
}
```

Generated classes are in package `com.robomart.proto.payment`.

### Avro Event Schemas (DO NOT MODIFY)

**`payment_processed.avsc`** — topic: `payment.payment.processed`
- Fields: eventId, eventType, aggregateId, aggregateType, timestamp, version, paymentId, orderId, amount, transactionId, status

**`payment_refunded.avsc`** — topic: `payment.payment.refunded`
- Fields: eventId, eventType, aggregateId, aggregateType, timestamp, version, paymentId, orderId, amount, refundTransactionId

### Idempotency Pattern — Implementation Details

```
ProcessPayment flow:
1. Check IdempotencyKeyRepository.findByIdempotencyKey(key)
2. If found AND expiresAt > Instant.now():
   → Return cached response (deserialize from idempotencyKey.response)
   → DO NOT create new Payment or OutboxEvent
3. If found BUT expired:
   → Treat as new request (delete expired key first)
4. If not found:
   → Create Payment (status=PENDING initially)
   → Call MockPaymentGateway.processPayment()
   → On success: update Payment status=COMPLETED, set transactionId
   → Create IdempotencyKey with response=serialized ProcessPaymentResponse, expiresAt=now+24h
   → Create OutboxEvent for payment_processed
   → All within single @Transactional (or TransactionTemplate)
5. On gateway transient failure:
   → Set Payment status=FAILED
   → Do NOT store idempotency key (allow retry with same key)
   → Throw PaymentTransientException
6. On gateway permanent failure:
   → Set Payment status=FAILED
   → Store idempotency key with failure response (prevent re-attempt)
   → Throw PaymentDeclinedException
```

### Retry Architecture Note

The retry with exponential backoff (FR30: max 3 retries, 1s initial, 2x multiplier) is the **caller's responsibility** (Order Saga Orchestrator in Story 4.4). This story's job is to:
- Correctly signal transient vs permanent failures via gRPC status codes
- Allow idempotent retry (same idempotency_key returns same result when payment succeeded)
- NOT store idempotency key on transient failure (so retries can re-attempt)

### gRPC Error Mapping

| Java Exception | gRPC Status | When |
|---------------|-------------|------|
| `PaymentDeclinedException` | `FAILED_PRECONDITION` | Permanent payment failure (declined, fraud) |
| `PaymentTransientException` | `UNAVAILABLE` | Transient failure (timeout, temp error) — caller should retry |
| `ResourceNotFoundException` | `NOT_FOUND` | Payment not found (for refund) |
| `IllegalArgumentException` | `INVALID_ARGUMENT` | Bad request data |
| `IllegalStateException` | `FAILED_PRECONDITION` | Invalid payment state for operation (e.g. refund non-COMPLETED) |
| Other `RuntimeException` | `INTERNAL` | Unexpected error |

### MockPaymentGateway Design

- A simple `@Service` that simulates an external payment gateway
- `processPayment(BigDecimal amount, String currency)` → returns `GatewayResult(transactionId, status)`
- `refundPayment(String transactionId, BigDecimal amount)` → returns `GatewayResult(refundTransactionId, status)`
- Default: always succeeds. Use constructor/config to inject failure scenarios for testing
- Throws `PaymentTransientException` for transient failures (simulates timeout/network error)
- Throws `PaymentDeclinedException` for permanent failures (simulates declined card)
- Add configurable delay (Thread.sleep or similar) to simulate real gateway latency (50-200ms)

### OutboxEvent Creation Pattern (Follow Inventory Service Pattern)

```java
// Use tools.jackson.databind.ObjectMapper (Jackson 3.x, NOT com.fasterxml.jackson.databind)
Map<String, Object> payload = new HashMap<>();
payload.put("paymentId", String.valueOf(payment.getId()));
payload.put("orderId", payment.getOrderId());
payload.put("amount", payment.getAmount().toPlainString());
payload.put("transactionId", payment.getTransactionId());
payload.put("status", payment.getStatus().name());

OutboxEvent event = new OutboxEvent(
    "Payment",                                    // aggregateType
    String.valueOf(payment.getId()),               // aggregateId
    "payment_processed",                           // eventType
    objectMapper.writeValueAsString(payload)        // payload JSON
);
outboxEventRepository.save(event);
```

### Project Structure Notes

New files to create (all under `backend/payment-service/src/main/java/com/robomart/payment/`):

```
service/
├── PaymentService.java          # Business logic with idempotency
└── MockPaymentGateway.java      # Mock payment gateway
exception/
├── PaymentDeclinedException.java    # Permanent failure
└── PaymentTransientException.java   # Transient (retryable) failure
```

Modify:
```
grpc/
└── PaymentGrpcService.java      # Replace UNIMPLEMENTED with real logic
```

Test files (all under `backend/payment-service/src/test/java/com/robomart/payment/`):

```
unit/
├── service/
│   ├── MockPaymentGatewayTest.java
│   └── PaymentServiceTest.java
└── grpc/
    └── PaymentGrpcServiceTest.java
integration/
├── PaymentServiceIT.java
└── PaymentGrpcIT.java
```

### Technology Constraints

- **Spring Boot 4.0.4** / Spring Framework 7.x / Java 21
- **Jakarta EE 11**: all imports use `jakarta.*` (NOT `javax.*`)
- **Jackson 3.x**: `tools.jackson.databind.ObjectMapper` (NOT `com.fasterxml.jackson.databind`)
- **Spring gRPC 1.0.x**: `@GrpcService` annotation, extend `*ImplBase`
- **Transactions**: Use `TransactionTemplate` (programmatic) like Inventory Service — proven pattern from Story 4.2
- **Testing**: AssertJ assertions, `should{Expected}When{Condition}()` naming, Testcontainers for integration
- **Exceptions**: extend from `common-lib` base exceptions (`ExternalServiceException`, `ResourceNotFoundException`)
- **MapStruct**: NOT needed for this story (gRPC proto ↔ domain mapping is simple enough inline)

### Previous Story Learnings (from Story 4.2)

1. Use `TransactionTemplate` instead of `@Transactional` when wrapping transactions with external operations (proven in Inventory Service)
2. Use `tools.jackson.databind.ObjectMapper` for Jackson 3.x — Spring Boot 4 auto-configures the correct bean
3. Annotations still from `com.fasterxml.jackson.annotation` (unchanged in Jackson 3)
4. For gRPC integration tests: use `GrpcServerLifecycle.getPort()` — `@LocalGrpcPort` is NOT available in Spring gRPC 1.0.2
5. Use `lenient()` for conditional stubs in Mockito to avoid UnnecessaryStubbingException
6. OutboxEvent constructor pattern: `new OutboxEvent(aggregateType, aggregateId, eventType, jsonPayload)`

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Epic 4, Story 4.3]
- [Source: _bmad-output/planning-artifacts/architecture.md — Payment Service directory structure, gRPC contracts, Outbox pattern, error handling, testing standards]
- [Source: _bmad-output/planning-artifacts/prd.md — FR28, FR29, FR30, FR31, FR32, NFR45]
- [Source: backend/proto/src/main/proto/payment_service.proto — gRPC contract]
- [Source: backend/events/src/main/avro/payment/ — Avro schemas]
- [Source: backend/inventory-service/src/main/java/.../service/InventoryService.java — TransactionTemplate + OutboxEvent pattern]
- [Source: _bmad-output/implementation-artifacts/4-2-implement-inventory-service-with-distributed-locking.md — Dev learnings]

## Dev Agent Record

### Agent Model Used
Claude Opus 4.6

### Debug Log References
- All 33 unit tests pass (8 MockPaymentGateway + 14 PaymentService + 11 PaymentGrpcService)
- All 13 integration tests pass (6 PaymentServiceIT + 7 PaymentGrpcIT)
- Full regression suite: 46 tests, 0 failures, BUILD SUCCESS (post code-review fixes)

### Completion Notes List
- Implemented MockPaymentGateway with configurable success/failure simulation, unique transactionId generation, and refund support
- Implemented PaymentService with full idempotency pattern: check key → return cached or process new → store key with 24h TTL
- Transient failures do NOT store idempotency key (allows retry); permanent failures DO store it (prevents re-attempt)
- PaymentService uses TransactionTemplate (programmatic transactions) following proven Inventory Service pattern
- OutboxEvent creation follows same pattern as Inventory Service with Jackson 3.x ObjectMapper
- PaymentGrpcService replaces UNIMPLEMENTED stubs with full exception-to-gRPC-status mapping per error mapping table
- PaymentResult record used for serialization/deserialization of cached idempotency responses
- Integration tests use Testcontainers PostgreSQL and GrpcServerLifecycle.getPort() for gRPC port discovery

### Change Log
- 2026-03-31: Story 4.3 implemented — all 7 tasks completed, 40 tests passing
- 2026-03-31: Code review — 5 patches applied (retry-after-failure, race condition, atomic expired key delete, input validation, refund amount validation), 1 deferred (clock skew). 6 new unit tests added, integration test workaround removed. 46 tests passing

### File List

New files:
- backend/payment-service/src/main/java/com/robomart/payment/exception/PaymentDeclinedException.java
- backend/payment-service/src/main/java/com/robomart/payment/exception/PaymentTransientException.java
- backend/payment-service/src/main/java/com/robomart/payment/service/GatewayResult.java
- backend/payment-service/src/main/java/com/robomart/payment/service/MockPaymentGateway.java
- backend/payment-service/src/main/java/com/robomart/payment/service/PaymentService.java
- backend/payment-service/src/test/java/com/robomart/payment/unit/service/MockPaymentGatewayTest.java
- backend/payment-service/src/test/java/com/robomart/payment/unit/service/PaymentServiceTest.java
- backend/payment-service/src/test/java/com/robomart/payment/unit/grpc/PaymentGrpcServiceTest.java
- backend/payment-service/src/test/java/com/robomart/payment/integration/PaymentServiceIT.java
- backend/payment-service/src/test/java/com/robomart/payment/integration/PaymentGrpcIT.java

Modified files:
- backend/payment-service/src/main/java/com/robomart/payment/grpc/PaymentGrpcService.java
