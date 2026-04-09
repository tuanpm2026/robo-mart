# Story 6.3: Implement Dead Letter Queue for Failed Events

Status: done

## Story

As a system,
I want failed Kafka events to be captured in a Dead Letter Queue for later reprocessing,
so that no events are lost when processing fails.

## Acceptance Criteria

1. **Given** a Kafka consumer in Notification Service **when** event processing fails after max retries **then** the failed event is routed to topic `notification.dlq` with original event payload, error message, exception class, retry count, and timestamps (FR54)
2. **Given** `notification.dlq` topic **when** retained in Kafka **then** it has a minimum 7-day retention period (retention.ms = 604800000) (NFR28)
3. **Given** `DlqConsumer` listening on `notification.dlq` **when** a DLQ message arrives **then** it logs the full debug context: original topic, partition, offset, failure reason, exception class, retry count, first failure timestamp, last failure timestamp, consumer group, aggregate ID
4. **Given** DLQ message headers **when** inspected **then** each includes: `kafka_dlt-original-topic`, `kafka_dlt-original-partition`, `kafka_dlt-original-offset`, `kafka_dlt-exception-message`, `kafka_dlt-exception-fqcn`, `kafka_dlt-original-consumer-group`, `kafka_delivery_attempt`, `x-dlq-first-failure-timestamp`, `x-dlq-last-failure-timestamp`

## Tasks / Subtasks

- [x] Task 1: Configure `notification.dlq` Kafka topic with 7-day retention (AC: #1, #2)
  - [x] Create `KafkaDlqConfig.java` in `backend/notification-service/src/main/java/com/robomart/notification/config/`
  - [x] Declare `@Bean NewTopic notificationDlqTopic()` — `TopicBuilder.name("notification.dlq").partitions(1).replicas(1).config(TopicConfig.RETENTION_MS_CONFIG, "604800000").build()`
  - [x] Spring Boot 4 auto-configures `KafkaAdmin` from `spring.kafka.bootstrap-servers` — no explicit `KafkaAdmin` bean needed

- [x] Task 2: Create DLQ Kafka producer infrastructure in `KafkaDlqConfig.java` (AC: #1, #4)
  - [x] `@Bean ProducerFactory<String, Object> dlqProducerFactory()` — same props as main producers: `StringSerializer` key, `KafkaAvroSerializer` value, `AUTO_REGISTER_SCHEMAS=true` (must match because failed events are already-deserialized Avro SpecificRecord objects)
  - [x] `@Bean KafkaTemplate<String, Object> dlqKafkaTemplate(@Qualifier("dlqProducerFactory") ProducerFactory<String, Object> dlqProducerFactory)`
  - [x] `@Bean DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(@Qualifier("dlqKafkaTemplate") KafkaTemplate<String, Object> dlqKafkaTemplate)` — destination resolver routes all failures to `new TopicPartition("notification.dlq", 0)`, headersFunction adds `x-dlq-first-failure-timestamp` and `x-dlq-last-failure-timestamp` (both set to `Instant.now().toString()` at recovery time)

- [x] Task 3: Create DLQ consumer infrastructure in `KafkaDlqConfig.java` (AC: #3)
  - [x] `@Bean ConsumerFactory<String, Object> dlqConsumerFactory()` — same Avro deserializer props as main consumer but with `SPECIFIC_AVRO_READER_CONFIG = false` (returns `GenericRecord` instead of specific class — handles any Avro event type without knowing schema upfront)
  - [x] `@Bean ConcurrentKafkaListenerContainerFactory<String, Object> dlqListenerContainerFactory(@Qualifier("dlqConsumerFactory") ConsumerFactory<String, Object> dlqConsumerFactory)` — plain factory, no error handler (DLQ consumer is log-only, must not itself fail)

- [x] Task 4: Update `KafkaConsumerConfig.java` to wire DLQ recoverer (AC: #1)
  - [x] Change `kafkaListenerContainerFactory()` to accept `DeadLetterPublishingRecoverer dlqRecoverer` as parameter
  - [x] Change `DefaultErrorHandler` construction to: `new DefaultErrorHandler(dlqRecoverer, new FixedBackOff(1000L, 3))` — keeps 3 retries at 1s interval
  - [x] Enable delivery attempt header: `factory.getContainerProperties().setDeliveryAttemptHeader(true)` — in Spring Kafka 4.0.4, this is on `ContainerProperties`, not `DefaultErrorHandler`

- [x] Task 5: Create `DlqConsumer.java` in `backend/notification-service/src/main/java/com/robomart/notification/event/` (AC: #3, #4)
  - [x] `@KafkaListener(topics = "notification.dlq", groupId = "notification-dlq-monitor-group", containerFactory = "dlqListenerContainerFactory")`
  - [x] Method signature: `public void onDlqMessage(ConsumerRecord<String, Object> record)` — import `org.apache.kafka.clients.consumer.ConsumerRecord`
  - [x] Extract headers using `record.headers().lastHeader(key)` → `new String(header.value(), StandardCharsets.UTF_8)` — use `"unknown"` fallback when header absent
  - [x] Log ALL required fields at `log.error(...)` level: `originalTopic`, `partition`, `offset`, `exceptionClass` (`kafka_dlt-exception-fqcn`), `exceptionMessage` (`kafka_dlt-exception-message`), `retryCount` (`kafka_delivery_attempt`), `firstFailureTs` (`x-dlq-first-failure-timestamp`), `lastFailureTs` (`x-dlq-last-failure-timestamp`), `consumerGroup` (`kafka_dlt-original-consumer-group`), `aggregateId` (`record.key()`)
  - [x] Private `extractHeader(Headers headers, String key)` helper

- [x] Task 6: Unit test `DlqConsumerTest.java` in `backend/notification-service/src/test/java/com/robomart/notification/unit/` (AC: #3, #4)
  - [x] Test: `shouldLogAllDltHeadersOnDlqMessage` — build `ConsumerRecord<String, Object>` with `RecordHeaders` containing all DLT headers, call `onDlqMessage()`, verify no exception thrown (logging-only)
  - [x] Test: `shouldHandleMissingHeadersGracefully` — build `ConsumerRecord` with empty headers, call `onDlqMessage()`, verify no NPE/exception thrown

- [x] Task 7: Integration test `DlqRoutingIT.java` in `backend/notification-service/src/test/java/com/robomart/notification/integration/` (AC: #1, #2, #3)
  - [x] Use `@SpringBootTest` + `@Import({PostgresContainerConfig.class, KafkaContainerConfig.class, TestKafkaProducerConfig.class})`
  - [x] `@MockitoBean NotificationService notificationService` — mock to throw `RuntimeException("simulated failure")` on all methods via `doThrow(...).when(notificationService).sendOrderConfirmedNotifications(any())`
  - [x] Inner `@TestConfiguration` class with `@KafkaListener` on `notification.dlq` using `dlqListenerContainerFactory` → stores received `ConsumerRecord` in `BlockingQueue<ConsumerRecord<String, Object>>`
  - [x] Test: `shouldRoutePoisonEventToDlqAfterMaxRetries` — send `OrderStatusChangedEvent(orderId="dlq-test-1", newStatus="CONFIRMED")` to `order.order.status-changed`, await `blockingQueue.poll(30, SECONDS)` not null, assert `kafka_dlt-original-topic` header = `order.order.status-changed`
  - [x] **Important**: retries take 3 × 1s = 3s minimum — use `await().atMost(60, SECONDS)` to give headroom; do NOT reduce FixedBackOff interval in application — use real config

### Review Findings

- [x] [Review][Decision] DLQ consumer factory has no error handler — added `DefaultErrorHandler(FixedBackOff(0, 0))` to log-and-skip on deserialization failures [KafkaDlqConfig.java:dlqListenerContainerFactory]
- [x] [Review][Patch] `kafka_dlt-original-partition` and `kafka_dlt-original-offset` headers are binary-encoded (ByteBuffer int/long) — added `extractIntHeader`/`extractLongHeader` methods with ByteBuffer decoding and UTF-8 fallback [DlqConsumer.java]
- [x] [Review][Patch] `extractHeader` null `header.value()` guard — added null check on value in all extract methods [DlqConsumer.java]
- [x] [Review][Patch] `DlqTestListener` redundant `@Component` removed — kept `@TestConfiguration` only [DlqRoutingIT.java]
- [x] [Review][Defer] No metrics/alerting in DlqConsumer — only logs, no Micrometer counters [DlqConsumer.java] — deferred, out of scope for this story
- [x] [Review][Defer] No `@DirtiesContext` or test isolation for DLQ state across integration tests [DlqRoutingIT.java] — deferred, pre-existing pattern
- [x] [Review][Defer] `TestKafkaProducerConfig` bean name `producerFactory` could conflict with `dlqProducerFactory` without `@Primary` — deferred, pre-existing, works with @Qualifier

## Dev Notes

### Architecture

- **DLQ scope**: Only Notification Service consumers write to `notification.dlq`. Inventory/Cart service failures do NOT use this DLQ (they have their own outbox retry).
- **At-least-once delivery**: `DeadLetterPublishingRecoverer` is the official Spring Kafka mechanism for DLQ routing. Never implement a custom recoverer — reuse what exists.
- **Topic auto-creation**: `NewTopic` bean + `KafkaAdmin` (auto-configured by Spring Boot from `spring.kafka.bootstrap-servers`) auto-creates `notification.dlq` on startup if it doesn't exist.
- **Retention**: `TopicConfig.RETENTION_MS_CONFIG` constant is in `org.apache.kafka.common.config.TopicConfig`.

### Spring Kafka 4.0.4 API Notes

- `DefaultErrorHandler` constructor: `new DefaultErrorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff)` — `DeadLetterPublishingRecoverer` implements `ConsumerRecordRecoverer`
- `DeadLetterPublishingRecoverer` constructor: takes `KafkaTemplate<?, ?>` + `BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver`
- `setHeadersFunction(BiFunction<ConsumerRecord<?, ?>, Exception, Headers> headersFunction)` — returns additional headers to add (merged with default DLT headers)
- `setDeliveryAttemptHeader(true)` on `DefaultErrorHandler` → enables `kafka_delivery_attempt` header
- `KafkaHeaders` constants: `DLT_ORIGINAL_TOPIC`, `DLT_ORIGINAL_PARTITION`, `DLT_ORIGINAL_OFFSET`, `DLT_ORIGINAL_TIMESTAMP`, `DLT_EXCEPTION_MESSAGE`, `DLT_EXCEPTION_FQCN`, `DLT_ORIGINAL_CONSUMER_GROUP` — all in `org.springframework.kafka.support.KafkaHeaders`

### Critical: Producer Type Mismatch

**MUST use `KafkaAvroSerializer` for the DLQ producer** — not `StringSerializer` or `ByteArraySerializer`. The `DeadLetterPublishingRecoverer` receives the already-deserialized Avro `SpecificRecord` object (e.g., `OrderStatusChangedEvent`) and re-serializes it using the DLQ template's serializer. If you use `ByteArraySerializer`, the serialize call will fail with `ClassCastException`.

### Critical: `@Qualifier` for Multiple Beans of Same Type

Both `KafkaConsumerConfig` and `KafkaDlqConfig` declare `ConsumerFactory<String, Object>` beans. When injecting into `dlqListenerContainerFactory`:
```java
// Use @Qualifier to avoid NoUniqueBeanDefinitionException:
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Object> dlqListenerContainerFactory(
        @Qualifier("dlqConsumerFactory") ConsumerFactory<String, Object> dlqConsumerFactory)
```
Same pattern for `ProducerFactory<String, Object> dlqProducerFactory` in `dlqKafkaTemplate`.

### Critical: `SPECIFIC_AVRO_READER_CONFIG` in DLQ Consumer

Main `consumerFactory` uses `SPECIFIC_AVRO_READER_CONFIG = true` — this returns specific generated classes (e.g., `OrderStatusChangedEvent`). DLQ consumer factory must use `SPECIFIC_AVRO_READER_CONFIG = false` to return `GenericRecord` (handles any Avro event without knowing the schema).

### File Locations

All new files in notification-service only:
```
backend/notification-service/src/main/java/com/robomart/notification/
  config/
    KafkaDlqConfig.java        ← NEW
  event/
    DlqConsumer.java           ← NEW
  config/
    KafkaConsumerConfig.java   ← MODIFY (wire dlqRecoverer)
backend/notification-service/src/test/java/com/robomart/notification/
  unit/
    DlqConsumerTest.java       ← NEW
  integration/
    DlqRoutingIT.java          ← NEW
```

No changes to: `pom.xml` (all deps already present: spring-kafka, kafka-avro-serializer), `application.yml`, other services.

### Testing Patterns (from Story 6.1, 6.2)

- Unit tests in `src/test/java/.../unit/`, no Spring context — pure Mockito (`@ExtendWith(MockitoExtension.class)`)
- Integration tests in `src/test/java/.../integration/` — use `@SpringBootTest` + `@ActiveProfiles("test")` + `@Import` Testcontainers configs
- `ConsumerRecord` test construction: `new ConsumerRecord<String, Object>("topic", 0, 0L, "key", value)` — then add headers: `record.headers().add("headerName", "value".getBytes(StandardCharsets.UTF_8))`
- Avoid `@MockBean` (deprecated in Spring Boot 4) — use `@MockitoBean`
- Do NOT add new Testcontainer configs — reuse existing `PostgresContainerConfig` and `KafkaContainerConfig` from `test-support`

### Existing Error Handler (reference)

Current `KafkaConsumerConfig.kafkaListenerContainerFactory()`:
```java
factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3)));
```
After this story:
```java
DefaultErrorHandler errorHandler = new DefaultErrorHandler(dlqRecoverer, new FixedBackOff(1000L, 3));
errorHandler.setDeliveryAttemptHeader(true);
factory.setCommonErrorHandler(errorHandler);
```

### References

- Spring Kafka 4.0.4 — `DeadLetterPublishingRecoverer` [Source: architecture.md#Event-Driven Communication]
- FR54: System can queue failed events in Dead Letter Queue [Source: epics.md#Epic-6-Story-6.3]
- NFR28: DLQ messages retained for minimum 7 days [Source: epics.md#NFR28]
- `TopicConfig.RETENTION_MS_CONFIG` = `org.apache.kafka.common.config.TopicConfig` [Source: architecture.md#Kafka]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Spring Kafka 4.0.4: `setDeliveryAttemptHeader` is on `ContainerProperties` (via `factory.getContainerProperties()`), NOT on `DefaultErrorHandler` as story notes suggested.

### Completion Notes List

- Implemented `KafkaDlqConfig.java` with 4 beans: `notificationDlqTopic` (7-day retention), `dlqProducerFactory` (KafkaAvroSerializer), `dlqKafkaTemplate`, `deadLetterPublishingRecoverer` (routes to `notification.dlq:0`, adds custom timestamp headers), `dlqConsumerFactory` (SPECIFIC_AVRO_READER=false for GenericRecord), `dlqListenerContainerFactory`.
- Updated `KafkaConsumerConfig.kafkaListenerContainerFactory()` to accept `DeadLetterPublishingRecoverer` and enable delivery attempt header via `ContainerProperties`.
- Created `DlqConsumer.java` — logs all 10 required fields from DLT headers at ERROR level; graceful fallback to "unknown" for missing headers.
- Unit tests: 2 tests pass (all-headers case + missing-headers case); 18 total unit tests pass, no regressions.
- Integration test `DlqRoutingIT.java` written and compiled; requires Docker daemon to run (consistent with all other integration tests in this service).

### File List

- `backend/notification-service/src/main/java/com/robomart/notification/config/KafkaDlqConfig.java` (NEW)
- `backend/notification-service/src/main/java/com/robomart/notification/config/KafkaConsumerConfig.java` (MODIFIED)
- `backend/notification-service/src/main/java/com/robomart/notification/event/DlqConsumer.java` (NEW)
- `backend/notification-service/src/test/java/com/robomart/notification/unit/DlqConsumerTest.java` (NEW)
- `backend/notification-service/src/test/java/com/robomart/notification/integration/DlqRoutingIT.java` (NEW)
- `_bmad-output/implementation-artifacts/6-3-implement-dead-letter-queue-for-failed-events.md` (MODIFIED)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED)

## Change Log

- 2026-04-09: Implemented DLQ for Notification Service — added KafkaDlqConfig (topic, producer, recoverer, consumer), updated KafkaConsumerConfig to wire DLQ recoverer with delivery attempt header, created DlqConsumer for structured error logging, added 2 unit tests + 1 integration test.
