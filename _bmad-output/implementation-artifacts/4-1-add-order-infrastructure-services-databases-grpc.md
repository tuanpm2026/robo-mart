# Story 4.1: Add Order Infrastructure — Services, Databases & gRPC

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want Order Service, Inventory Service, and Payment Service scaffolded with their databases and gRPC contracts,
So that the Saga-based order flow can be implemented in subsequent stories.

## Acceptance Criteria (BDD)

1. **Given** Docker Compose **When** updated for this story **Then** 3 additional PostgreSQL instances are added: `order_db` (port 5434), `inventory_db` (port 5435), `payment_db` (port 5436)

2. **Given** proto module **When** implemented for this story **Then** it contains: `inventory_service.proto` (ReserveInventory, ReleaseInventory RPCs), `payment_service.proto` (ProcessPayment, RefundPayment RPCs), `order_service.proto` (CreateOrder, GetOrder, CancelOrder RPCs), `common/types.proto` (Money, Address messages) **And** protobuf-maven-plugin generates Java stubs

3. **Given** Order Service module **When** created with Flyway migrations **Then** tables are created: `orders`, `order_items`, `order_status_history`, `outbox_events`, `saga_audit_log` **And** seed data (`demo` profile) loads sample orders in various states (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED)

4. **Given** Inventory Service module **When** created with Flyway migrations **Then** tables are created: `inventory_items`, `stock_movements`, `outbox_events` **And** seed data loads inventory quantities matching the ~50 seed products

5. **Given** Payment Service module **When** created with Flyway migrations **Then** tables are created: `payments`, `idempotency_keys`, `outbox_events`

6. **Given** events module **When** updated for this story **Then** Avro schemas added: `order/` (order_created, order_status_changed, order_cancelled), `inventory/` (stock_reserved, stock_released, stock_low_alert), `payment/` (payment_processed, payment_refunded)

7. **Given** all three services **When** started **Then** each exposes gRPC endpoints, connects to its database, and runs Flyway migrations on startup

## Tasks / Subtasks

### Task 1: Docker Compose — Add 3 PostgreSQL Instances (AC: #1)

- [ ] 1.1 Add `postgres-order` container to `infra/docker/docker-compose.yml`:
  - Image: `postgres:17`, profile: `core`
  - DB: `order_db`, port: `5434:5432`
  - Volume: `order-data`, network: `robomart-network`
  - Same healthcheck pattern as existing `postgres` container
- [ ] 1.2 Add `postgres-inventory` container: DB `inventory_db`, port `5435:5432`, volume `inventory-data`
- [ ] 1.3 Add `postgres-payment` container: DB `payment_db`, port `5436:5432`, volume `payment-data`
- [ ] 1.4 Add 3 new volumes to the `volumes:` section

### Task 2: Proto Module — gRPC Contracts (AC: #2)

- [ ] 2.1 Add protobuf-maven-plugin and grpc-java plugin to `backend/proto/pom.xml`:
  - Dependencies: `grpc-protobuf`, `grpc-stub`, `protobuf-java`
  - Plugin: `protobuf-maven-plugin` with `protoc` and `grpc-java` plugin
  - Use `os-maven-plugin` for platform-specific protoc binary
- [ ] 2.2 Create `backend/proto/src/main/proto/common/types.proto`:
  - Package: `com.robomart.proto.common`
  - Messages: `Money` (string currency, string amount), `Address` (fields: street, city, state, zip, country)
- [ ] 2.3 Create `backend/proto/src/main/proto/inventory_service.proto`:
  - Package: `com.robomart.proto.inventory`
  - Service `InventoryService` with RPCs:
    - `ReserveInventory(ReserveInventoryRequest) returns (ReserveInventoryResponse)`
    - `ReleaseInventory(ReleaseInventoryRequest) returns (ReleaseInventoryResponse)`
    - `GetInventory(GetInventoryRequest) returns (GetInventoryResponse)`
  - Import `common/types.proto`
- [ ] 2.4 Create `backend/proto/src/main/proto/payment_service.proto`:
  - Package: `com.robomart.proto.payment`
  - Service `PaymentService` with RPCs:
    - `ProcessPayment(ProcessPaymentRequest) returns (ProcessPaymentResponse)`
    - `RefundPayment(RefundPaymentRequest) returns (RefundPaymentResponse)`
- [ ] 2.5 Create `backend/proto/src/main/proto/order_service.proto`:
  - Package: `com.robomart.proto.order`
  - Service `OrderService` with RPCs:
    - `CreateOrder(CreateOrderRequest) returns (CreateOrderResponse)`
    - `GetOrder(GetOrderRequest) returns (GetOrderResponse)`
    - `CancelOrder(CancelOrderRequest) returns (CancelOrderResponse)`
- [ ] 2.6 Remove placeholder `package-info.java`, verify `mvn compile` generates stubs

### Task 3: Events Module — Avro Schemas (AC: #6)

- [ ] 3.1 Create `backend/events/src/main/avro/order/order_created.avsc`:
  - Namespace: `com.robomart.events.order`
  - Fields: orderId, userId, items (array), totalAmount, status, timestamp
- [ ] 3.2 Create `order_status_changed.avsc`: orderId, previousStatus, newStatus, timestamp
- [ ] 3.3 Create `order_cancelled.avsc`: orderId, reason, cancelledBy, timestamp
- [ ] 3.4 Create `backend/events/src/main/avro/inventory/stock_reserved.avsc`:
  - Namespace: `com.robomart.events.inventory`
  - Fields: orderId, productId, quantity, timestamp
- [ ] 3.5 Create `stock_released.avsc`: orderId, productId, quantity, reason, timestamp
- [ ] 3.6 Create `stock_low_alert.avsc`: productId, currentQuantity, threshold, timestamp
- [ ] 3.7 Create `backend/events/src/main/avro/payment/payment_processed.avsc`:
  - Namespace: `com.robomart.events.payment`
  - Fields: paymentId, orderId, amount, transactionId, status, timestamp
- [ ] 3.8 Create `payment_refunded.avsc`: paymentId, orderId, amount, refundTransactionId, timestamp
- [ ] 3.9 Verify `mvn compile` generates Avro classes for all schemas

### Task 4: Order Service — Module Scaffold (AC: #3, #7)

- [ ] 4.1 Create `backend/order-service/pom.xml` with dependencies:
  - `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`
  - `spring-boot-starter-grpc` (Spring gRPC 1.0.x — auto-configured by Spring Boot 4)
  - Internal: `common-lib`, `security-lib`, `proto`, `events`
  - DB: `postgresql` driver
  - Test: `spring-boot-starter-test`, `test-support`
- [ ] 4.2 Create `OrderServiceApplication.java` in `com.robomart.order`
- [ ] 4.3 Create `application.yml` and `application-demo.yml`:
  - Server port: `8083`
  - gRPC port: `9093`
  - Datasource: `jdbc:postgresql://localhost:5434/order_db`
  - Flyway enabled, Kafka bootstrap-servers
  - Demo profile: enable seed data migration
- [ ] 4.4 Create standard service package structure:
  ```
  com.robomart.order/
  ├── config/         # Service configuration
  ├── controller/     # REST controllers
  ├── dto/            # Request/Response DTOs
  ├── entity/         # JPA entities
  ├── enums/          # OrderStatus enum
  ├── exception/      # Domain exceptions
  ├── grpc/           # gRPC service implementation
  ├── mapper/         # MapStruct mappers
  ├── repository/     # Spring Data JPA repositories
  ├── saga/           # Saga orchestrator (placeholder for Story 4.4)
  └── service/        # Business logic
  ```
- [ ] 4.5 Create JPA entities:
  - `Order` (id, userId, totalAmount, status, shippingAddress, createdAt, updatedAt)
  - `OrderItem` (id, orderId, productId, productName, quantity, unitPrice, subtotal)
  - `OrderStatusHistory` (id, orderId, status, changedAt)
  - `OutboxEvent` (id, aggregateType, aggregateId, eventType, payload, createdAt, published, publishedAt)
  - `SagaAuditLog` (id, sagaId, orderId, stepName, status, request, response, error, executedAt)
- [ ] 4.6 Create `OrderStatus` enum: PENDING, INVENTORY_RESERVING, PAYMENT_PROCESSING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, PAYMENT_REFUNDING, INVENTORY_RELEASING
- [ ] 4.7 Create repositories: `OrderRepository`, `OrderItemRepository`, `OrderStatusHistoryRepository`, `OutboxEventRepository`, `SagaAuditLogRepository`
- [ ] 4.8 Create Flyway migration `V1__init_order_schema.sql`
- [ ] 4.9 Create seed data `R__seed_orders.sql` (demo profile): ~10 sample orders across all statuses with items

### Task 5: Inventory Service — Module Scaffold (AC: #4, #7)

- [ ] 5.1 Create `backend/inventory-service/pom.xml` with dependencies:
  - Same as order-service + `spring-boot-starter-data-redis` (for distributed locking in Story 4.2)
- [ ] 5.2 Create `InventoryServiceApplication.java` in `com.robomart.inventory`
- [ ] 5.3 Create `application.yml` and `application-demo.yml`:
  - Server port: `8084`, gRPC port: `9094`
  - Datasource: `jdbc:postgresql://localhost:5435/inventory_db`
- [ ] 5.4 Create package structure: config, controller, dto, entity, enums, exception, grpc, mapper, repository, service
- [ ] 5.5 Create JPA entities:
  - `InventoryItem` (id, productId, availableQuantity, reservedQuantity, totalQuantity, lowStockThreshold, updatedAt)
  - `StockMovement` (id, inventoryItemId, type, quantity, orderId, reason, createdAt)
  - `OutboxEvent` (same structure as order-service)
- [ ] 5.6 Create `StockMovementType` enum: RESERVE, RELEASE, RESTOCK, ADJUSTMENT
- [ ] 5.7 Create repositories: `InventoryItemRepository`, `StockMovementRepository`, `OutboxEventRepository`
- [ ] 5.8 Create Flyway migration `V1__init_inventory_schema.sql`
- [ ] 5.9 Create seed data `R__seed_inventory.sql`: inventory for all ~50 seed products (matching product_db seed data)

### Task 6: Payment Service — Module Scaffold (AC: #5, #7)

- [ ] 6.1 Create `backend/payment-service/pom.xml` (similar to order-service, no Redis needed)
- [ ] 6.2 Create `PaymentServiceApplication.java` in `com.robomart.payment`
- [ ] 6.3 Create `application.yml` and `application-demo.yml`:
  - Server port: `8085`, gRPC port: `9095`
  - Datasource: `jdbc:postgresql://localhost:5436/payment_db`
- [ ] 6.4 Create package structure: config, controller, dto, entity, enums, exception, grpc, mapper, repository, service
- [ ] 6.5 Create JPA entities:
  - `Payment` (id, orderId, amount, currency, status, transactionId, idempotencyKey, createdAt, updatedAt)
  - `IdempotencyKey` (id, idempotencyKey, orderId, response, expiresAt, createdAt)
  - `OutboxEvent` (same structure)
- [ ] 6.6 Create `PaymentStatus` enum: PENDING, PROCESSING, COMPLETED, FAILED, REFUNDED
- [ ] 6.7 Create repositories: `PaymentRepository`, `IdempotencyKeyRepository`, `OutboxEventRepository`
- [ ] 6.8 Create Flyway migration `V1__init_payment_schema.sql`

### Task 7: Parent POM — Register New Modules (AC: #7)

- [ ] 7.1 Add `<module>order-service</module>`, `<module>inventory-service</module>`, `<module>payment-service</module>` to `backend/pom.xml`
- [ ] 7.2 Add internal module version management in dependencyManagement if needed

### Task 8: gRPC Server Stubs — Minimal Implementation (AC: #7)

- [ ] 8.1 Create `InventoryGrpcService.java` in inventory-service — extends generated `InventoryServiceGrpc.InventoryServiceImplBase`, all RPCs return UNIMPLEMENTED status (placeholder for Story 4.2)
- [ ] 8.2 Create `PaymentGrpcService.java` in payment-service — placeholder, UNIMPLEMENTED (Story 4.3)
- [ ] 8.3 Create `OrderGrpcService.java` in order-service — placeholder, UNIMPLEMENTED (Story 4.4)

### Task 9: API Gateway — Route Configuration (AC: #7)

- [ ] 9.1 Add gateway routes for order-service endpoints in `application.yml`:
  - `/api/v1/orders/**` → order-service (authenticated, CUSTOMER role)
  - `/api/v1/admin/orders/**` → order-service (authenticated, ADMIN role)
- [ ] 9.2 Update `GatewaySecurityConfig.java` with order endpoint security rules

### Task 10: Build Verification (AC: all)

- [ ] 10.1 Run `mvn compile` from backend root — all modules compile including generated proto stubs and Avro classes
- [ ] 10.2 Start each new service individually — verify Flyway migrations run, gRPC server starts, healthcheck passes
- [ ] 10.3 Verify `docker compose --profile core up -d` starts all containers (including 3 new PostgreSQL instances)

### Review Findings

- [x] [Review][Patch] F1: Replace `@Service` with `@GrpcService` on all 3 gRPC service implementations — FIXED
- [x] [Review][Patch] F2: Add `@CreationTimestamp`/`@UpdateTimestamp` annotations to standalone entities — FIXED
- [x] [Review][Patch] F3: Add `@JdbcTypeCode(SqlTypes.JSON)` to OutboxEvent.payload field in all 3 services — FIXED
- [x] [Review][Patch] F4: Add parameterized constructor and `markPublished()` method to OutboxEvent in all 3 services — FIXED
- [x] [Review][Patch] F5: Fix order seed data total_amount mismatches — FIXED (Orders 1→164.97, 3→334.98, 9→129.97, 10→509.96)
- [x] [Review][Patch] F6: Remove redundant indexes on UNIQUE columns — FIXED
- [x] [Review][Patch] F7: Add empty `demo` profile section to payment-service application.yml — FIXED
- [x] [Review][Defer] F8: InventoryItem.productId (Long) vs proto/Avro product_id (String) type mismatch — by design per spec, mapping layer to be added when gRPC is implemented in later stories [inventory/entity/InventoryItem.java:22]
- [x] [Review][Defer] F9: Outbox index uses single-column (published) instead of composite (published, created_at) — consistent with existing product-service pattern, optimize later if needed [V1__init_*_schema.sql]
- [x] [Review][Defer] F10: SagaAuditLog.orderId VARCHAR vs orders.id BIGINT — by design for cross-service string references per proto convention [order/entity/SagaAuditLog.java]
- [x] [Review][Defer] F11: Proto timestamps use int64 instead of google.protobuf.Timestamp — acceptable for infrastructure story, can be updated in future proto evolution [order_service.proto]
- [x] [Review][Defer] F12: SQL tables missing CHECK constraints for quantities and amounts — consistent with existing product-service pattern, can be added later [V1__init_*_schema.sql]

## Dev Notes

### Critical: Spring Boot 4 + Spring gRPC 1.0.x

Spring Boot 4.0.4 has official Spring gRPC support via `spring-grpc` project (NOT the old `grpc-spring-boot-starter` from LogNet):

- Starter: `org.springframework.grpc:spring-grpc-spring-boot-starter` (check if bundled via Spring Boot 4 dependency management, or add explicit version)
- Auto-configures gRPC server on a separate port
- `application.yml` config: `spring.grpc.server.port: 9093`
- Service implementation: annotate with `@GrpcService` and extend generated `*ImplBase`
- **IMPORTANT**: If Spring gRPC 1.0.x is not yet stable/available, fallback to `net.devh:grpc-server-spring-boot-starter:3.x` (grpc-ecosystem) which is compatible with Spring Boot 4

### Critical: Protobuf Maven Plugin Configuration

```xml
<plugin>
    <groupId>io.grpc</groupId>
    <artifactId>protoc-gen-grpc-java</artifactId>
    <!-- version managed by Spring Boot or explicit -->
</plugin>
<plugin>
    <groupId>org.xolstice.maven.plugins</groupId>
    <artifactId>protobuf-maven-plugin</artifactId>
    <configuration>
        <protocArtifact>com.google.protobuf:protoc:${protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
    </configuration>
</plugin>
```
Requires `os-maven-plugin` extension in proto module's POM for `${os.detected.classifier}`.

### Critical: Existing Code — DO NOT Recreate

| Component | Path | Reuse |
|-----------|------|-------|
| common-lib | `backend/common-lib/` | `ApiResponse`, error codes, exception hierarchy, `@ControllerAdvice` |
| security-lib | `backend/security-lib/` | `KeycloakRealmRoleConverter`, JWT config, security filters |
| events module | `backend/events/` | `BaseEventEnvelope.avsc`, Avro plugin config — extend, don't replace |
| proto module | `backend/proto/` | POM exists (empty) — add proto plugin + .proto files |
| Parent POM | `backend/pom.xml` | dependencyManagement, properties — add new modules |
| Docker Compose | `infra/docker/docker-compose.yml` | Add new containers, don't modify existing ones |
| API Gateway | `backend/api-gateway/` | `GatewaySecurityConfig.java` — add order routes |
| Product Service | `backend/product-service/` | Reference for service structure patterns |
| Cart Service | `backend/cart-service/` | Reference for Redis + service patterns |

### Critical: Database Schema Conventions

Follow existing Product Service patterns:
- Tables: `snake_case`, plural
- Primary keys: `id BIGSERIAL PRIMARY KEY`
- Foreign keys: `{referenced_table_singular}_id BIGINT REFERENCES {table}(id)`
- Indexes: `idx_{table}_{columns}`
- Unique constraints: `uk_{table}_{columns}`
- Timestamps: `TIMESTAMP NOT NULL DEFAULT NOW()`
- All monetary values: `DECIMAL(19,2)` (not FLOAT)
- Status columns: `VARCHAR(50) NOT NULL`

### Critical: OutboxEvent Entity Pattern

Each service that publishes Kafka events MUST have its own `outbox_events` table with identical schema:
```sql
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events(published, created_at) WHERE published = FALSE;
```
The outbox polling service will be implemented in later stories. This story only creates the table.

### Critical: Payment Service Port Conflict

Architecture specifies Payment Service at port 8085. Schema Registry currently uses port 8085. **Resolution**: Either change Payment Service to port `8086` or change Schema Registry port. Recommend changing Payment Service to `8086` to avoid breaking existing docker-compose config.

### Avro Schema Conventions

Follow existing `base_event_envelope.avsc` pattern:
- Namespace: `com.robomart.events.{domain}` (e.g., `com.robomart.events.order`)
- All schemas are standalone records (not wrapped in BaseEventEnvelope — envelope is applied at runtime by the Outbox publisher)
- Timestamp fields: `"type": "long", "logicalType": "timestamp-millis"`
- Amount/money fields: `"type": "string"` (serialized BigDecimal)
- Required string fields: `"type": "string"` (not union with null)
- Optional fields: `"type": ["null", "string"], "default": null`

### gRPC Proto Conventions

- Package: `com.robomart.proto.{domain}`
- java_package option: `option java_package = "com.robomart.proto.{domain}";`
- java_outer_classname: `option java_outer_classname = "{Domain}ServiceProto";`
- Service naming: `{Domain}Service` (PascalCase)
- RPC naming: `{VerbNoun}` (PascalCase) — e.g., `ReserveInventory`, `ProcessPayment`
- Message naming: `{RpcName}Request`, `{RpcName}Response` (PascalCase)
- Field naming: `snake_case` (Protobuf convention)
- Money fields: use `common.Money` message type
- IDs: `string` type (for flexibility)

### Service Port Assignments

| Service | HTTP Port | gRPC Port | DB Port |
|---------|-----------|-----------|---------|
| Product Service | 8081 | — | 5432 (existing) |
| Cart Service | 8082 | — | — (Redis 6379) |
| API Gateway | 8080 | — | — |
| **Order Service** | **8083** | **9093** | **5434** |
| **Inventory Service** | **8084** | **9094** | **5435** |
| **Payment Service** | **8086** | **9095** | **5436** |

### Seed Data Strategy

- **Order Service** (demo profile): ~10 orders across statuses with 2-3 items each, referencing existing product IDs from product_db seed data
- **Inventory Service** (demo profile): 1 inventory_item per seed product (~50 records), random quantities 0-500, some below low-stock threshold (10)
- **Payment Service**: No seed data — payments are created by Saga

### Spring Boot 4 Specifics to Remember

- Jakarta EE 11: all imports use `jakarta.*` (not `javax.*`)
- Jackson 3.x: `tools.jackson.databind` for databind, `com.fasterxml.jackson.annotation` for annotations
- `@MockitoBean` instead of deprecated `@MockBean`
- Flyway starter: `spring-boot-starter-flyway` (not raw `flyway-core`)
- No `TestRestTemplate` — use `RestClient` for integration tests
- Java 21 LTS (architecture says 25 but dev machine uses 21)

### Project Structure Notes

New files to create:
```
infra/docker/docker-compose.yml                    # MODIFY (add 3 PostgreSQL containers)

backend/pom.xml                                    # MODIFY (add 3 modules)

backend/proto/pom.xml                              # MODIFY (add protobuf-maven-plugin)
backend/proto/src/main/proto/common/types.proto    # NEW
backend/proto/src/main/proto/inventory_service.proto # NEW
backend/proto/src/main/proto/payment_service.proto   # NEW
backend/proto/src/main/proto/order_service.proto     # NEW

backend/events/src/main/avro/order/                # NEW (3 schemas)
backend/events/src/main/avro/inventory/            # NEW (3 schemas)
backend/events/src/main/avro/payment/              # NEW (2 schemas)

backend/order-service/                             # NEW (full module)
backend/inventory-service/                         # NEW (full module)
backend/payment-service/                           # NEW (full module)

backend/api-gateway/.../GatewaySecurityConfig.java # MODIFY (add order routes)
backend/api-gateway/.../application.yml            # MODIFY (add order routes)
```

### References

- [Source: epics.md#Epic-4, Story 4.1]
- [Source: architecture.md#Technical-Stack — Spring Boot 4.0.4, Spring gRPC 1.0.x]
- [Source: architecture.md#Database-Schemas — Order/Inventory/Payment tables]
- [Source: architecture.md#gRPC-Services — service contracts and mapping]
- [Source: architecture.md#Kafka-Events — topic naming and Avro schemas]
- [Source: architecture.md#Docker-Compose-Profiles — core profile containers]
- [Source: architecture.md#Project-Structure — service directory layout]
- [Source: architecture.md#Naming-Patterns — DB, Proto, Avro conventions]

### Previous Story Intelligence (Story 3.4)

- **Service scaffold pattern**: Follow product-service and cart-service structure — `@SpringBootApplication`, `application.yml` with profiles, Flyway migrations
- **API Gateway security**: Rules order matters — specific paths BEFORE wildcards in `GatewaySecurityConfig.java`
- **Test patterns**: Unit tests with `@ExtendWith(MockitoExtension.class)`, integration tests with `@IntegrationTest` + `RestClient` + `@LocalServerPort`
- **Test naming**: `should{Expected}When{Condition}()`, AssertJ assertions, TestData builders
- **Review findings from 3.4**: Validate input sizes (`@Size(max=128)`), use typed assertions not string matching

### Git Intelligence

Recent commits follow pattern: `feat: <action> (<Story X.Y>)`. This story should produce a commit like:
`feat: add Order/Inventory/Payment infrastructure with gRPC contracts (Story 4.1)`

Files recently modified: api-gateway, cart-service, customer-website — none conflict with this story's scope.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
