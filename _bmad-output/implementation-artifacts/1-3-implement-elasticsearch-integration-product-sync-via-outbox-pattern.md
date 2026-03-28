# Story 1.3: Implement Elasticsearch Integration & Product Sync via Outbox Pattern

Status: done

## Story

As a customer,
I want product data to be searchable via Elasticsearch with changes synced from PostgreSQL within 30 seconds,
so that I can perform fast full-text search across up-to-date product data.

## Acceptance Criteria

1. **Index Creation**: Given Elasticsearch is running, when Product Service starts, then Elasticsearch product index is created with proper field mappings (name, description, category, brand, price, rating as searchable/filterable fields)

2. **Avro Schemas**: Given the events module, when this story is complete, then product Avro schemas are added: `product_created.avsc`, `product_updated.avsc`, `product_deleted.avsc` under `events/src/main/avro/product/`

3. **Outbox Publishing**: Given a product exists in PostgreSQL, when the Outbox poller runs (1s polling interval, batch size 50), then the product event is published to Kafka topic `product.product.created` in Avro format via Schema Registry, and the outbox record is marked `published=true` with `published_at` timestamp

4. **Real-time Sync**: Given a product is updated in PostgreSQL, when the Outbox poller publishes the update event, then the corresponding Elasticsearch document is updated within 30 seconds of the database change (FR5)

5. **Cleanup Job**: Given published outbox events older than 7 days, when the daily cleanup job runs, then they are deleted from the `outbox_events` table

6. **Seed Data Indexing**: Given seed data loaded via `demo` profile, when all outbox events are processed, then all ~50 seed products are indexed in Elasticsearch and searchable

## Tasks / Subtasks

- [x] Task 1: Add Avro product event schemas to events module (AC: #2)
  - [x] 1.1 Create `events/src/main/avro/product/product_created.avsc` with fields: eventId, eventType, aggregateId, aggregateType, timestamp, version, payload (product snapshot: id, sku, name, description, price, categoryId, categoryName, brand, rating, stockQuantity)
  - [x] 1.2 Create `events/src/main/avro/product/product_updated.avsc` (same payload structure as created)
  - [x] 1.3 Create `events/src/main/avro/product/product_deleted.avsc` with minimal payload (id, sku)
  - [x] 1.4 Verify Avro Maven plugin generates Java classes successfully (`mvn compile -pl events`)

- [x] Task 2: Add Kafka and Elasticsearch dependencies to product-service (AC: #1, #3)
  - [x] 2.1 Add to product-service `pom.xml`: `spring-boot-starter-data-elasticsearch`, `spring-kafka`, `kafka-avro-serializer`, events module dependency
  - [x] 2.2 Add Kafka producer configuration in `application.yml` (bootstrap-servers, schema-registry, Avro serializer, producer properties)
  - [x] 2.3 Add Elasticsearch configuration in `application.yml` (uris: localhost:9200)
  - [x] 2.4 Add test profile overrides in `application-test.yml` for Testcontainers

- [x] Task 3: Create Elasticsearch index mapping and document model (AC: #1)
  - [x] 3.1 Create `ProductDocument` class (Spring Data Elasticsearch `@Document`) with fields: id, sku, name (text+keyword), description (text), categoryId, categoryName (keyword), brand (keyword), price (double), rating (double), stockQuantity (integer), createdAt, updatedAt
  - [x] 3.2 Create `ProductSearchRepository` extending `ElasticsearchRepository<ProductDocument, Long>`
  - [x] 3.3 Create `ElasticsearchConfig.java` in config/ package for any custom index settings (analyzer, mapping)
  - [x] 3.4 Verify index is auto-created on Product Service startup with correct mappings

- [x] Task 4: Implement OutboxEvent entity and repository (AC: #3)
  - [x] 4.1 Create `OutboxEvent` JPA entity mapping to existing `outbox_events` table (id, aggregateType, aggregateId, eventType, payload as JSONB, createdAt, published, publishedAt)
  - [x] 4.2 Create `OutboxEventRepository` with query methods: `findTop50ByPublishedFalseOrderByCreatedAtAsc()`, `deleteByPublishedTrueAndPublishedAtBefore(Instant cutoff)`
  - [x] 4.3 Create `OutboxPublisher` service — method `saveEvent(aggregateType, aggregateId, eventType, payload)` that persists to outbox table within the caller's transaction

- [x] Task 5: Implement Kafka event producer (AC: #3)
  - [x] 5.1 Create `ProductEventProducer` in `event/producer/` — publishes Avro-serialized events to Kafka topics via `KafkaTemplate`
  - [x] 5.2 Topic naming: `product.product.created`, `product.product.updated`, `product.product.deleted`
  - [x] 5.3 Set Kafka message key = aggregateId for partition ordering
  - [x] 5.4 Propagate correlation headers: `x-correlation-id`, `x-trace-id`

- [x] Task 6: Implement OutboxPollingService (AC: #3, #4)
  - [x] 6.1 Create `OutboxPollingService` with `@Scheduled(fixedDelay = 1000)` — polls unpublished events batch of 50
  - [x] 6.2 For each event: deserialize payload, build Avro record, publish via `ProductEventProducer`, mark `published=true` with `publishedAt=Instant.now()`
  - [x] 6.3 Handle publish failures: log error, skip event (will retry on next poll), do NOT mark as published
  - [x] 6.4 Wrap batch processing in transaction for atomicity of status updates

- [x] Task 7: Implement Elasticsearch sync consumer (AC: #4)
  - [x] 7.1 Create `ProductIndexConsumer` in `event/consumer/` with `@KafkaListener` for topics `product.product.created`, `product.product.updated`, `product.product.deleted`
  - [x] 7.2 Consumer group: `product-service-product-index-group`
  - [x] 7.3 On created/updated: map Avro event payload to `ProductDocument`, save to Elasticsearch via `ProductSearchRepository`
  - [x] 7.4 On deleted: remove document from Elasticsearch by id
  - [x] 7.5 Implement idempotency: use eventId to skip duplicate processing

- [x] Task 8: Implement outbox cleanup scheduled job (AC: #5)
  - [x] 8.1 Create `OutboxCleanupService` with `@Scheduled(cron = "0 0 2 * * *")` — runs daily at 2 AM
  - [x] 8.2 Delete published outbox events where `publishedAt < Instant.now().minus(7, ChronoUnit.DAYS)`
  - [x] 8.3 Log number of cleaned-up records

- [x] Task 9: Implement seed data indexing for demo profile (AC: #6)
  - [x] 9.1 Create `SeedDataIndexer` component active only with `demo` profile (`@Profile("demo")`)
  - [x] 9.2 On `ApplicationReadyEvent`: query all products from PostgreSQL, create outbox events for each, let the poller index them
  - [x] 9.3 Alternative approach: directly bulk-index all products to Elasticsearch on startup (simpler for seed data)

- [x] Task 10: Add Testcontainers configs for Kafka and Elasticsearch (AC: all)
  - [x] 10.1 Create `KafkaContainerConfig` in test-support module — Kafka + Schema Registry containers with `@ServiceConnection`
  - [x] 10.2 Create `ElasticsearchContainerConfig` in test-support module — Elasticsearch container with `@ServiceConnection`
  - [x] 10.3 Update `@IntegrationTest` annotation to import new container configs
  - [x] 10.4 Add testcontainers-kafka and testcontainers-elasticsearch to test-support pom.xml

- [x] Task 11: Write unit tests (AC: all)
  - [x] 11.1 `OutboxPublisherTest` — verify event persistence with correct fields
  - [x] 11.2 `OutboxPollingServiceTest` — verify polling, publishing, marking published, error handling
  - [x] 11.3 `OutboxCleanupServiceTest` — verify cleanup logic with date threshold
  - [x] 11.4 `ProductEventProducerTest` — verify Kafka message construction, topic routing, headers

- [x] Task 12: Write integration tests (AC: all)
  - [x] 12.1 `OutboxPollingServiceIT` — end-to-end: save product with outbox event → poll → verify Kafka message received
  - [x] 12.2 `ProductIndexConsumerIT` — send Kafka event → verify Elasticsearch document created/updated/deleted
  - [x] 12.3 `OutboxCleanupServiceIT` — insert old published events → run cleanup → verify deletion
  - [x] 12.4 `ElasticsearchIndexIT` — verify index creation on startup, field mappings correct

### Review Findings

- [x] [Review][Patch] createdAt lost on ProductDocument update — fixed: read existing doc to preserve createdAt [ProductIndexConsumer.java:77-88]
- [x] [Review][Patch] No consumer error handler / DLT — fixed: added DefaultErrorHandler with FixedBackOff(1000ms, 3 retries) [KafkaConsumerConfig.java:42-46]
- [x] [Review][Patch] `.get()` without timeout blocks scheduler indefinitely — fixed: added .get(10, TimeUnit.SECONDS) [OutboxPollingService.java:87,108,121]
- [x] [Review][Patch] Unknown event types silently marked as published — fixed: throw IllegalStateException for unknown types [OutboxPollingService.java:123]
- [x] [Review][Patch] Long-held DB transaction wraps all Kafka sends — fixed: removed @Transactional, save each event individually after publish [OutboxPollingService.java:41-42]
- [x] [Review][Patch] Null safety in toLong/toInt — fixed: added null checks with descriptive IllegalArgumentException [OutboxPollingService.java:127-139]
- [x] [Review][Patch] OutboxPublisher missing @Transactional(MANDATORY) — fixed: added @Transactional(propagation = MANDATORY) [OutboxPublisher.java:21]
- [x] [Review][Patch] Missing x-correlation-id in Kafka headers — fixed: read from MDC and add header [ProductEventProducer.java:37-41]
- [x] [Review][Defer] No distributed lock on outbox polling [OutboxPollingService.java] — deferred, single-instance deployment for now
- [x] [Review][Defer] Consumer idempotency via eventId not implemented [ProductIndexConsumer.java] — deferred, ES save by ID is inherently idempotent
- [x] [Review][Defer] Auto-register schemas should be disabled in production [application.yml] — deferred, production config concern
- [x] [Review][Defer] Corrupt events retry forever without retry count limit [OutboxPollingService.java] — deferred, needs retry count column design

## Dev Notes

### Architecture Compliance

- **Package structure**: `com.robomart.product.{config,controller,dto,entity,exception,mapper,repository,service,event}` — follow existing pattern from Story 1.2
- **Layered architecture**: Controller → Service → Repository → Entity (no business logic in controllers)
- **Outbox Pattern**: Transactional outbox — write domain entity + outbox event in SAME transaction. Poller reads unpublished events and publishes to Kafka. This guarantees at-least-once delivery.
- **Event flow**: Product write (DB + outbox) → OutboxPoller (1s) → Kafka → ProductIndexConsumer → Elasticsearch
- **Self-consumption**: Product Service both produces AND consumes its own events for Elasticsearch sync. Consumer group `product-service-product-index-group` ensures this works correctly.

### Critical Technical Requirements

**Outbox table already exists** (V4 migration from Story 1.2):
```sql
CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events(published) WHERE published = FALSE;
```

**Avro schema conventions** [Source: architecture.md#Kafka Event Naming Conventions]:
- Namespace: `com.robomart.events.product`
- Record names: `ProductCreatedEvent`, `ProductUpdatedEvent`, `ProductDeletedEvent`
- Location: `events/src/main/avro/product/`
- Base envelope fields: eventId (string), eventType (string), aggregateId (string), aggregateType (string), timestamp (long/timestamp-millis), version (int), payload (domain-specific record)

**Kafka topic naming**: `{service}.{entity}.{event}` → `product.product.created`, `product.product.updated`, `product.product.deleted`

**Kafka producer config**:
- Key serializer: StringSerializer (aggregateId)
- Value serializer: KafkaAvroSerializer (Avro record)
- Schema Registry URL: `http://localhost:8085`
- `acks=all` for durability
- Headers: `x-correlation-id`, `x-trace-id`, `x-user-id`

**Elasticsearch field mappings** for product index:
| Field | ES Type | Purpose |
|-------|---------|---------|
| name | text + keyword | Full-text search + exact match |
| description | text | Full-text search |
| categoryName | keyword | Filter/aggregation |
| categoryId | long | Filter |
| brand | keyword | Filter/aggregation |
| price | double | Range filter/sort |
| rating | double | Range filter/sort |
| stockQuantity | integer | Availability filter |
| sku | keyword | Exact match |
| createdAt | date | Sort |
| updatedAt | date | Sort |

**Docker infrastructure already configured** [Source: infra/docker/docker-compose.yml]:
- Elasticsearch 8.17.0 on port 9200 (xpack.security=false)
- Kafka 7.9.0 (KRaft) on port 29092
- Schema Registry 7.9.0 on port 8085
- All in `core` profile

### Jackson 3.x Warning (Spring Boot 4)

Any custom Jackson code MUST use `tools.jackson.databind` package — NOT `com.fasterxml.jackson.databind`. Annotations remain in `com.fasterxml.jackson.annotation` (unchanged). The common-lib JacksonConfig already handles global configuration. Do NOT add jackson-datatype-jsr310 — Java Time is built into Jackson 3.x.

### Existing Components to REUSE (DO NOT recreate)

- `BaseEntity` (common-lib) — id, createdAt, updatedAt with @CreationTimestamp/@UpdateTimestamp
- `ApiResponse<T>`, `PagedResponse<T>` (common-lib) — response wrappers
- `GlobalExceptionHandler` (common-lib) — centralized error handling
- `CorrelationIdFilter` (common-lib) — propagates X-Correlation-Id
- `JacksonConfig` (common-lib) — camelCase, NON_NULL, ISO dates
- `BaseEventEnvelope.avsc` (events module) — reference for event structure
- `Product`, `Category`, `ProductImage` entities — existing from Story 1.2
- `ProductRepository`, `CategoryRepository` — existing JPA repositories
- `ProductMapper` (MapStruct) — existing mapper
- `ProductService` — existing service (will be modified to write outbox events)
- `TestData` builders (test-support) — for creating test entities
- `PostgresContainerConfig` (test-support) — existing Testcontainer config
- `@IntegrationTest` annotation (test-support) — existing test annotation

### What NOT to Implement (Out of Scope)

- Search API endpoint (GET /api/v1/products/search) — Story 1.4
- Redis caching for search results — Story 2.3
- Cache invalidation via Kafka — Story 2.3
- GraphQL endpoint — Story 1.5
- Admin product CRUD (write endpoints) — Epic 5
- Spring Security / JWT — Epic 3
- Debezium CDC migration — architecture Phase 2 (optional future)
- Circuit breaker / fallback to PostgreSQL when ES is down — Epic 8

### Testing Requirements

**Test naming**: `should{Expected}When{Condition}()` — e.g., `shouldPublishEventWhenOutboxPollerRuns()`

**Assertions**: AssertJ only — `assertThat(result).isEqualTo(expected)`, NOT JUnit assertions

**Test data**: Use `TestData` builders from test-support module

**Testcontainers**:
- PostgreSQL 17 (existing `PostgresContainerConfig`)
- Kafka + Schema Registry (new `KafkaContainerConfig` — use `ConfluentKafkaContainer` or `KafkaContainer` from testcontainers-kafka)
- Elasticsearch (new `ElasticsearchContainerConfig` — use `ElasticsearchContainer` from testcontainers-elasticsearch)
- All containers as singletons (static instances, started once)
- Container reuse: `testcontainers.reuse.enable=true`

**RestClient pattern** for HTTP tests (Spring Boot 4 — no TestRestTemplate):
```java
restClient = RestClient.builder()
    .baseUrl("http://localhost:" + port)
    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
    .build();
```

**Coverage target**: 80% minimum for new code

### Modifying Existing Code

**ProductService** must be modified to write outbox events when products are created/updated/deleted. Currently read-only — Story 1.2 only implemented GET endpoints. Story 1.3 adds the outbox event writing capability that will be triggered by admin CRUD in Epic 5. For now, the seed data indexer will create outbox events for existing products.

### Performance Requirements

- Kafka event processing lag < 5 seconds (NFR7)
- Eventual consistency window < 30 seconds (NFR32, FR5)
- Outbox poller: 1s interval, batch 50
- Service startup < 30s (NFR8) — Elasticsearch index creation must not slow startup

### Project Structure Notes

New files to create in product-service:
```
product-service/src/main/java/com/robomart/product/
├── config/
│   └── ElasticsearchConfig.java          # NEW
├── entity/
│   └── OutboxEvent.java                  # NEW
├── repository/
│   ├── OutboxEventRepository.java        # NEW
│   └── ProductSearchRepository.java      # NEW (Elasticsearch)
├── service/
│   ├── OutboxPublisher.java              # NEW
│   ├── OutboxPollingService.java         # NEW
│   ├── OutboxCleanupService.java         # NEW
│   └── ProductIndexService.java          # NEW (optional helper)
├── event/
│   ├── producer/
│   │   └── ProductEventProducer.java     # NEW
│   └── consumer/
│       └── ProductIndexConsumer.java     # NEW
└── document/
    └── ProductDocument.java              # NEW (ES @Document)
```

New files in events module:
```
events/src/main/avro/product/
├── product_created.avsc                  # NEW
├── product_updated.avsc                  # NEW
└── product_deleted.avsc                  # NEW
```

New files in test-support:
```
test-support/src/main/java/com/robomart/test/
├── KafkaContainerConfig.java             # NEW
└── ElasticsearchContainerConfig.java     # NEW
```

### References

- [Source: architecture.md#Outbox Pattern Implementation] — outbox schema, polling config, cleanup
- [Source: architecture.md#Kafka Event Naming Conventions] — topic naming, Avro namespace, record naming
- [Source: architecture.md#Kafka Topic to Producer/Consumer Mapping] — product event topics and consumers
- [Source: architecture.md#Product Service structure] — file organization, packages
- [Source: architecture.md#Testcontainers Strategy] — singleton containers, reuse, container configs
- [Source: architecture.md#Test Naming Convention] — should{Expected}When{Condition}()
- [Source: prd.md#FR5] — 30-second sync window
- [Source: prd.md#FR55] — Outbox Pattern guarantee
- [Source: prd.md#NFR7] — Kafka lag < 5 seconds
- [Source: prd.md#NFR27] — No data loss via Outbox
- [Source: prd.md#NFR32] — 30-second eventual consistency
- [Source: epics.md#Story 1.3] — acceptance criteria, story definition
- [Source: story 1.2] — existing code patterns, entity structure, test infrastructure

### Previous Story Intelligence (Story 1.2)

Key learnings to apply:
1. **Jackson 3.x**: Use `tools.jackson.databind` package. `JsonMapperBuilderCustomizer` from `org.springframework.boot.jackson.autoconfigure`. No `jackson-datatype-jsr310`.
2. **BaseEntity**: `setId()` is protected, no `setCreatedAt`/`setUpdatedAt`. Use `@CreationTimestamp`/`@UpdateTimestamp`.
3. **RestClient for tests**: Spring Boot 4 removed `TestRestTemplate`. Use `RestClient` with `defaultStatusHandler` for error suppression.
4. **Testcontainers 2.x**: Artifact names changed — `testcontainers-postgresql` (not `postgresql`). Use `@ServiceConnection` for auto-configuration.
5. **MapStruct 1.6.3**: `@Mapper(componentModel = "spring")` for Spring injection.
6. **`@Version` for optimistic locking**: Product entity already has `version` field.
7. **scanBasePackages**: `@SpringBootApplication(scanBasePackages = "com.robomart")` already configured — beans from common-lib and events are auto-detected.
8. **Structured logging**: logback-spring.xml with LogstashEncoder (JSON prod, console dev).
9. **commons-logging**: Do NOT ban in maven-enforcer-plugin.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
