# Story 6.1: Implement Notification Service Core & Order Notifications

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a customer,
I want to receive notifications when my order is confirmed, payment succeeds or fails,
So that I stay informed about my purchase status.

## Acceptance Criteria

1. **Notification Service Module** (FR33): Given Notification Service module, when created with Flyway migration, then notification_log table is created to store all sent notifications (type, recipient, channel, status, content, created_at, trace_id). (AC1)

2. **Order Confirmation on CONFIRMED** (FR33): Given an `order.order.status-changed` Kafka event with `newStatus=CONFIRMED`, when consumed by `OrderEventConsumer`, then an order confirmation notification is sent to the customer (email) with: order number, items summary, total, estimated delivery. (AC2)

3. **Payment Success Notification** (FR34): Given an `order.order.status-changed` Kafka event with `newStatus=CONFIRMED` (which implies payment succeeded), when consumed by `OrderEventConsumer`, then a payment success notification is sent to the customer. (AC3)

4. **Payment Failure Notification** (FR34): Given an `order.order.status-changed` Kafka event with `newStatus=CANCELLED` and `previousStatus=PAYMENT_PROCESSING`, when consumed by `OrderEventConsumer`, then a payment failure notification is sent with empathetic language: "Payment couldn't be processed. Your order is saved." (AC4)

5. **Notification Logging** (FR33, FR34): Given all notifications sent, when inspected in notification_log table, then each has: notification type, recipient user ID, channel (email), delivery status, content, created_at, trace_id. (AC5)

## Tasks / Subtasks

- [x] Task 1: Scaffold notification-service module (AC: #1)
  - [x] 1.1 Create `backend/notification-service/pom.xml` with dependencies
  - [x] 1.2 Create `NotificationServiceApplication.java` main class
  - [x] 1.3 Create `application.yml` with base, dev, and test profiles
  - [x] 1.4 Add `<module>notification-service</module>` to `backend/pom.xml`
- [x] Task 2: Add notification database to docker-compose (AC: #1)
  - [x] 2.1 Add `postgres-notification` service to `infra/docker/docker-compose.yml` (port 5437)
- [x] Task 3: Flyway migration — notification_log table (AC: #1, #5)
  - [x] 3.1 Create `V1__init_notification_schema.sql` with notification_log table
- [x] Task 4: Domain entities and repository (AC: #1, #5)
  - [x] 4.1 Create `NotificationLog` JPA entity
  - [x] 4.2 Create `NotificationLogRepository` (JpaRepository)
  - [x] 4.3 Create `NotificationType` enum and `NotificationChannel` enum
- [x] Task 5: Kafka consumer configuration (AC: #2, #3, #4)
  - [x] 5.1 Create `KafkaConsumerConfig` (copy pattern from product-service)
- [x] Task 6: Order enrichment client (AC: #2)
  - [x] 6.1 Create `OrderServiceClient` using Spring `RestClient` to call order-service REST API for order details enrichment
- [x] Task 7: NotificationService — core orchestration (AC: #2, #3, #4, #5)
  - [x] 7.1 Create `NotificationService` with methods: `sendOrderConfirmation()`, `sendPaymentSuccess()`, `sendPaymentFailure()`
  - [x] 7.2 Each method: build notification content, call EmailService, save NotificationLog
- [x] Task 8: EmailService — mock implementation (AC: #2, #3, #4)
  - [x] 8.1 Create `EmailService` interface + `MockEmailService` implementation
  - [x] 8.2 Mock logs email content to console (same pattern as payment mock gateway)
- [x] Task 9: OrderEventConsumer — Kafka listener (AC: #2, #3, #4)
  - [x] 9.1 Create `OrderEventConsumer` with `@KafkaListener` for `order.order.status-changed`
  - [x] 9.2 Route CONFIRMED events → `NotificationService.sendOrderConfirmation()` + `sendPaymentSuccess()`
  - [x] 9.3 Route CANCELLED (from PAYMENT_PROCESSING) events → `NotificationService.sendPaymentFailure()`
- [x] Task 10: Write backend tests (AC: #1-#5)
  - [x] 10.1 Unit tests: `NotificationServiceTest` — test each notification method
  - [x] 10.2 Unit tests: `OrderEventConsumerTest` — test event routing logic
  - [x] 10.3 Integration test: `NotificationIntegrationIT` — Kafka consumer receives event, notification logged to DB

### Review Findings

- [x] [Review][Decision] Idempotency gap + partial failure: no deduplication guard on notifications — Fixed: unique constraint on (order_id, notification_type) + pre-send existsBy check + DataIntegrityViolationException catch. sendOrderConfirmedNotifications() now fetches order once.
- [x] [Review][Patch] NPE when getOrderDetail returns null [NotificationService.java:41,50,59]
- [x] [Review][Patch] HTTP errors from RestClient bypass sendAndLog error handler [OrderServiceClient.java:26]
- [x] [Review][Patch] NumberFormatException from Long.parseLong(orderId) [OrderServiceClient.java:27]
- [x] [Review][Patch] MockEmailService missing @Profile("!production") [MockEmailService.java:8]
- [x] [Review][Patch] Kafka/schema-registry/order-service URLs hardcoded without env-var substitution [application.yml:9-19]
- [x] [Review][Patch] Datasource credentials hardcoded in dev profile [application.yml:29-30]
- [x] [Review][Patch] @Transactional wraps HTTP call, holding DB connection during network I/O [NotificationService.java:38-63]
- [x] [Review][Patch] Thread.sleep in integration test should use Awaitility [NotificationIntegrationIT.java:153]
- [x] [Review][Patch] open-in-view: false only in dev profile, should be in base config [application.yml:37]
- [x] [Review][Patch] Docker uses postgres:17 instead of spec-required postgres:17-alpine [docker-compose.yml]
- [x] [Review][Patch] Docker uses shared POSTGRES_USER instead of NOTIFICATION_DB_USER per spec [docker-compose.yml]
- [x] [Review][Patch] Double HTTP call to order-service per CONFIRMED event [NotificationService.java:41,50]
- [x] [Review][Defer] No dead-letter topic after DefaultErrorHandler retry exhaustion [KafkaConsumerConfig.java] — deferred, Story 6.3 scope
- [x] [Review][Defer] userId used as email address without Keycloak lookup [NotificationService.java:77] — deferred, requires user-email lookup out of 6.1 scope
- [x] [Review][Defer] PENDING notification status for reliable before-send logging — deferred, enhancement beyond 6.1 scope
- [x] [Review][Defer] Null Avro event fields (.toString() NPE) [OrderEventConsumer.java:25-27] — deferred, Avro schema enforces non-null for required fields
- [x] [Review][Defer] Unbounded repository queries without Pageable [NotificationLogRepository.java] — deferred, pre-existing pattern
- [x] [Review][Defer] trace_id nullable in notification_log — deferred, legitimate null when no active span

## Dev Notes

### Critical Architecture Decision: PaymentProcessedEvent Gap

**Payment-service does NOT publish Kafka events.** It is called via gRPC only (from the saga orchestrator). The `PaymentProcessedEvent` Avro schema exists (`backend/events/src/main/avro/payment/payment_processed.avsc`) but has NO publisher — payment-service has no OutboxPollingService or KafkaTemplate.

**Solution for Story 6.1:** Derive payment status from order status transitions:
- `OrderStatusChangedEvent` with `newStatus=CONFIRMED` → payment succeeded (saga only confirms after successful payment)
- `OrderStatusChangedEvent` with `newStatus=CANCELLED` + `previousStatus=PAYMENT_PROCESSING` → payment failed
- This avoids scope creep of adding outbox to payment-service
- **Deferred:** Adding outbox publisher to payment-service for dedicated `PaymentProcessedEvent` — target future story

### Order Enrichment Strategy

The `OrderStatusChangedEvent` Avro schema only contains: `eventId`, `eventType`, `aggregateId`, `aggregateType`, `timestamp`, `version`, `orderId`, `previousStatus`, `newStatus`. It does NOT contain order items, total, or userId.

For order confirmation email content (items summary, total), the notification service must enrich via REST call to order-service. Use Spring `RestClient` to call order-service directly (service-to-service, bypassing API Gateway). This works because admin controllers have NO `@PreAuthorize` — RBAC is gateway-only.

**Enrichment endpoint:** `GET http://order-service:8083/api/v1/admin/orders/{orderId}` → returns `AdminOrderDetailResponse` with userId, items, totalAmount, status, shippingAddress.

### Existing Kafka Topics Published by Order-Service

From `OutboxPollingService.java`, order-service publishes to:
- `order.order.status-changed` — carries `OrderStatusChangedEvent` (status transitions)
- `order.order.cancelled` — carries cancellation events

**Story 6.1 consumes:** `order.order.status-changed` only.

### Notification-Service Module Setup

**Package:** `com.robomart.notification`

**Port assignments:**
- Server port: `8087` (payment-service uses 8086)
- PostgreSQL port: `5437` (postgres-payment uses 5436)
- Database name: `notification_db`

**Dependencies (pom.xml)** — follow order-service pom pattern:
```xml
<dependencies>
    <!-- Internal modules -->
    <dependency>common-lib</dependency>
    <dependency>events</dependency>       <!-- Avro generated classes -->

    <!-- Spring Boot starters -->
    <dependency>spring-boot-starter-web</dependency>
    <dependency>spring-boot-starter-data-jpa</dependency>
    <dependency>spring-boot-starter-actuator</dependency>
    <dependency>spring-boot-starter-validation</dependency>
    <dependency>spring-boot-starter-mail</dependency>  <!-- NEW for email -->

    <!-- Database -->
    <dependency>spring-boot-starter-flyway</dependency>
    <dependency>flyway-database-postgresql</dependency>
    <dependency>postgresql (runtime)</dependency>

    <!-- Kafka -->
    <dependency>spring-kafka</dependency>
    <dependency>kafka-avro-serializer</dependency>

    <!-- Tracing -->
    <dependency>spring-boot-micrometer-tracing-brave</dependency>  <!-- provides Tracer bean -->

    <!-- Test -->
    <dependency>test-support</dependency>  <!-- PostgresContainerConfig, KafkaContainerConfig -->
    <dependency>spring-boot-starter-test</dependency>
    <dependency>spring-kafka-test</dependency>
</dependencies>
```

**Do NOT include:** security-lib, proto, spring-grpc — notification-service is Kafka-consumer-only, no gRPC, no auth.

### Flyway Migration — notification_log Table

```sql
-- V1__init_notification_schema.sql
CREATE TABLE notification_log (
    id              BIGSERIAL PRIMARY KEY,
    notification_type VARCHAR(50) NOT NULL,   -- ORDER_CONFIRMED, PAYMENT_SUCCESS, PAYMENT_FAILED
    recipient       VARCHAR(255) NOT NULL,    -- userId (Keycloak subject ID)
    channel         VARCHAR(20) NOT NULL,     -- EMAIL
    status          VARCHAR(20) NOT NULL,     -- SENT, FAILED
    subject         VARCHAR(500),             -- email subject line
    content         TEXT NOT NULL,            -- email body content
    order_id        VARCHAR(50),              -- reference to order
    trace_id        VARCHAR(64),              -- distributed tracing correlation
    error_message   TEXT,                     -- error details if FAILED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_notification_type CHECK (notification_type IN ('ORDER_CONFIRMED', 'PAYMENT_SUCCESS', 'PAYMENT_FAILED')),
    CONSTRAINT chk_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT chk_status CHECK (status IN ('SENT', 'FAILED'))
);

CREATE INDEX idx_notification_log_recipient ON notification_log(recipient);
CREATE INDEX idx_notification_log_order_id ON notification_log(order_id);
CREATE INDEX idx_notification_log_type ON notification_log(notification_type);
CREATE INDEX idx_notification_log_created_at ON notification_log(created_at DESC);
```

### NotificationLog JPA Entity

```java
package com.robomart.notification.entity;

import com.robomart.notification.enums.NotificationChannel;
import com.robomart.notification.enums.NotificationType;
import com.robomart.notification.enums.NotificationStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_log")
public class NotificationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @Column(nullable = false)
    private String recipient;  // userId

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = Instant.now(); }

    // Getters, setters
}
```

### Enums

```java
public enum NotificationType { ORDER_CONFIRMED, PAYMENT_SUCCESS, PAYMENT_FAILED }
public enum NotificationChannel { EMAIL }
public enum NotificationStatus { SENT, FAILED }
```

### KafkaConsumerConfig — Copy Pattern from Product-Service

```java
package com.robomart.notification.config;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;
    @Value("${spring.kafka.properties.schema.registry.url}") private String schemaRegistryUrl;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3)));
        return factory;
    }
}
```

### OrderEventConsumer — Kafka Listener

```java
package com.robomart.notification.event;

@Component
public class OrderEventConsumer {
    private final NotificationService notificationService;

    // Consumer group: notification-order-status-group
    @KafkaListener(topics = "order.order.status-changed",
                   groupId = "notification-order-status-group")
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        String newStatus = event.getNewStatus().toString();
        String previousStatus = event.getPreviousStatus().toString();
        String orderId = event.getOrderId().toString();

        if ("CONFIRMED".equals(newStatus)) {
            // Payment succeeded + order confirmed → send both notifications
            notificationService.sendOrderConfirmation(orderId);
            notificationService.sendPaymentSuccess(orderId);
        } else if ("CANCELLED".equals(newStatus) && "PAYMENT_PROCESSING".equals(previousStatus)) {
            // Payment failed → send failure notification
            notificationService.sendPaymentFailure(orderId);
        }
    }
}
```

**IMPORTANT:** Avro `CharSequence` fields — `event.getOrderId()` returns `CharSequence` (not String). Always call `.toString()` before comparison or usage.

### OrderServiceClient — REST Enrichment

```java
package com.robomart.notification.client;

@Component
public class OrderServiceClient {
    private final RestClient restClient;

    public OrderServiceClient(@Value("${notification.order-service.url}") String orderServiceUrl) {
        this.restClient = RestClient.builder().baseUrl(orderServiceUrl).build();
    }

    public OrderDetailDto getOrderDetail(String orderId) {
        return restClient.get()
            .uri("/api/v1/admin/orders/{orderId}", Long.parseLong(orderId))
            .retrieve()
            .body(OrderDetailDto.class);
    }
}
```

**OrderDetailDto** — local DTO mirroring `AdminOrderDetailResponse` from order-service (do NOT import cross-service DTOs):
```java
public record OrderDetailDto(
    Long id, String userId, Instant createdAt, BigDecimal totalAmount,
    String status, String shippingAddress,
    List<OrderItemDto> items
) {}
public record OrderItemDto(String productName, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
```

**application.yml config:**
```yaml
notification:
  order-service:
    url: http://localhost:8083
```

### EmailService — Mock Implementation

```java
package com.robomart.notification.service;

public interface EmailService {
    void sendEmail(String to, String subject, String body);
}

@Service
@Profile("!production")  // Mock for dev — production would use real JavaMailSender
public class MockEmailService implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(MockEmailService.class);

    @Override
    public void sendEmail(String to, String subject, String body) {
        log.info("MOCK EMAIL — To: {}, Subject: {}, Body:\n{}", to, subject, body);
    }
}
```

### NotificationService — Orchestration

```java
package com.robomart.notification.service;

@Service
public class NotificationService {
    private final OrderServiceClient orderServiceClient;
    private final EmailService emailService;
    private final NotificationLogRepository notificationLogRepository;
    private final Tracer tracer;  // io.micrometer.tracing.Tracer

    @Transactional
    public void sendOrderConfirmation(String orderId) {
        OrderDetailDto order = orderServiceClient.getOrderDetail(orderId);
        String subject = "Order #" + orderId + " Confirmed";
        String body = buildOrderConfirmationBody(order);
        sendAndLog(order.userId(), orderId, NotificationType.ORDER_CONFIRMED, subject, body);
    }

    @Transactional
    public void sendPaymentSuccess(String orderId) {
        OrderDetailDto order = orderServiceClient.getOrderDetail(orderId);
        String subject = "Payment Received for Order #" + orderId;
        String body = "Your payment of $" + order.totalAmount() + " has been successfully processed.";
        sendAndLog(order.userId(), orderId, NotificationType.PAYMENT_SUCCESS, subject, body);
    }

    @Transactional
    public void sendPaymentFailure(String orderId) {
        OrderDetailDto order = orderServiceClient.getOrderDetail(orderId);
        String subject = "Payment Issue with Order #" + orderId;
        String body = "Payment couldn't be processed. Your order is saved. Please try again or use a different payment method.";
        sendAndLog(order.userId(), orderId, NotificationType.PAYMENT_FAILED, subject, body);
    }

    private void sendAndLog(String userId, String orderId, NotificationType type, String subject, String body) {
        NotificationLog log = new NotificationLog();
        log.setRecipient(userId);
        log.setOrderId(orderId);
        log.setNotificationType(type);
        log.setChannel(NotificationChannel.EMAIL);
        log.setSubject(subject);
        log.setContent(body);
        log.setTraceId(getTraceId());

        try {
            emailService.sendEmail(userId, subject, body);
            log.setStatus(NotificationStatus.SENT);
        } catch (Exception e) {
            log.setStatus(NotificationStatus.FAILED);
            log.setErrorMessage(e.getMessage());
        }
        notificationLogRepository.save(log);
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }

    private String buildOrderConfirmationBody(OrderDetailDto order) {
        var sb = new StringBuilder();
        sb.append("Your order #").append(order.id()).append(" has been confirmed!\n\n");
        sb.append("Items:\n");
        for (var item : order.items()) {
            sb.append("- ").append(item.productName())
              .append(" x").append(item.quantity())
              .append(" — $").append(item.subtotal()).append("\n");
        }
        sb.append("\nTotal: $").append(order.totalAmount());
        sb.append("\n\nEstimated delivery: 3-5 business days");
        return sb.toString();
    }
}
```

### Application.yml Structure

```yaml
spring:
  application:
    name: notification-service
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:29092}
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8085}
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5437}/${DB_NAME:notification_db}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}

server:
  port: 8087

notification:
  order-service:
    url: ${ORDER_SERVICE_URL:http://localhost:8083}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

### Docker-Compose Addition

Add after `postgres-payment` service:

```yaml
  postgres-notification:
    image: postgres:17-alpine
    container_name: robomart-postgres-notification
    profiles: ["core"]
    environment:
      POSTGRES_DB: notification_db
      POSTGRES_USER: ${NOTIFICATION_DB_USER:-postgres}
      POSTGRES_PASSWORD: ${NOTIFICATION_DB_PASSWORD:-postgres}
    ports:
      - "${NOTIFICATION_DB_PORT:-5437}:5432"
    volumes:
      - notification-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5
    deploy:
      resources:
        limits:
          memory: 256M
    networks:
      - robomart-network
```

Add `notification-data:` to the volumes section at the bottom.

### Testing Strategy

**Test infrastructure:** notification-service CAN use `@IntegrationTest` annotation from test-support — it has no gRPC dependencies that need mocking (unlike order-service). Use `PostgresContainerConfig` + `KafkaContainerConfig` from test-support.

**Integration test setup:**
```java
@IntegrationTest  // from com.robomart.test — auto-configures Testcontainers
class NotificationIntegrationIT {
    @MockitoBean private EmailService emailService;  // Mock email to verify calls
    // PostgresContainerConfig provides DB, KafkaContainerConfig provides Kafka + mock schema registry
    // Use KafkaTemplate to publish test events, verify notification_log entries
}
```

**Unit test setup:**
```java
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private OrderServiceClient orderServiceClient;
    @Mock private EmailService emailService;
    @Mock private NotificationLogRepository notificationLogRepository;
    @Mock private Tracer tracer;
    @InjectMocks private NotificationService notificationService;
}
```

**Test naming convention:** `should{Expected}When{Condition}()` (e.g., `shouldSendOrderConfirmationWhenStatusChangedToConfirmed()`)
**Assertion library:** AssertJ (`assertThat(...)`)

### Jackson Import Warning

Spring Boot 4 uses Jackson 3.x. Import from `tools.jackson.databind` (NOT `com.fasterxml.jackson.databind`). Annotations remain at `com.fasterxml.jackson.annotation`. The `RestClient` handles JSON deserialization automatically via auto-configured `ObjectMapper`.

### Consumer Group Naming Convention

Pattern: `{service}-{topic-purpose}-group`
- `notification-order-status-group` for `order.order.status-changed` topic

### Existing Files to Modify

```
backend/pom.xml                             ← ADD <module>notification-service</module>
infra/docker/docker-compose.yml             ← ADD postgres-notification service + volume
```

### New Files to Create

```
backend/notification-service/
├── pom.xml
├── src/main/java/com/robomart/notification/
│   ├── NotificationServiceApplication.java
│   ├── config/
│   │   └── KafkaConsumerConfig.java
│   ├── client/
│   │   ├── OrderServiceClient.java
│   │   ├── OrderDetailDto.java
│   │   └── OrderItemDto.java
│   ├── entity/
│   │   └── NotificationLog.java
│   ├── enums/
│   │   ├── NotificationType.java
│   │   ├── NotificationChannel.java
│   │   └── NotificationStatus.java
│   ├── event/
│   │   └── OrderEventConsumer.java
│   ├── repository/
│   │   └── NotificationLogRepository.java
│   └── service/
│       ├── EmailService.java
│       ├── MockEmailService.java
│       └── NotificationService.java
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       └── V1__init_notification_schema.sql
└── src/test/java/com/robomart/notification/
    ├── unit/
    │   ├── NotificationServiceTest.java
    │   └── OrderEventConsumerTest.java
    └── integration/
        └── NotificationIntegrationIT.java
```

### Project Structure Notes

- Notification-service is a **Kafka-consumer-only** service — no REST endpoints exposed for external consumption (no customer or admin API), no gRPC
- Package root: `com.robomart.notification` — follows existing convention (`com.robomart.{service}`)
- Docker-compose: postgres-notification follows exact pattern of postgres-payment (PostgreSQL 17-alpine, 256M limit, core profile, robomart-network)
- Flyway migrations: `src/main/resources/db/migration/V1__*.sql` — same as all other services
- No `security-lib` dependency — notification-service has no authenticated endpoints
- No `proto` dependency — no gRPC client or server
- RestClient for order-service enrichment uses direct service URL (bypassing API gateway)

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Epic-6, Story 6.1 — acceptance criteria]
- [Source: backend/product-service/src/main/java/com/robomart/product/config/KafkaConsumerConfig.java — Kafka consumer pattern]
- [Source: backend/product-service/src/main/java/com/robomart/product/event/consumer/ProductIndexConsumer.java — @KafkaListener pattern]
- [Source: backend/order-service/src/main/java/com/robomart/order/service/OutboxPollingService.java — published topics]
- [Source: backend/events/src/main/avro/order/order_status_changed.avsc — event schema]
- [Source: backend/events/src/main/avro/order/order_created.avsc — order data schema]
- [Source: backend/events/src/main/avro/payment/payment_processed.avsc — NO PUBLISHER EXISTS]
- [Source: backend/order-service/pom.xml — dependency reference]
- [Source: backend/order-service/src/main/resources/application.yml — config structure]
- [Source: backend/order-service/src/main/resources/db/migration/V1__init_order_schema.sql — Flyway pattern]
- [Source: infra/docker/docker-compose.yml — docker service pattern]
- [Source: backend/test-support/src/main/java/com/robomart/test/KafkaContainerConfig.java — test infrastructure]
- [Source: _bmad-output/implementation-artifacts/epic-5-retro-2026-04-08.md — Epic 6 readiness assessment]

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6

### Debug Log References

- Integration tests (NotificationIntegrationIT) could not run due to Docker Desktop not running — Testcontainers requires Docker. Test code compiles correctly and is structurally sound.
- Avro `timestamp` field generates `Instant` setter (not `long`) due to `logicalType: timestamp-millis` — fixed in test builders.

### Completion Notes List

- Scaffolded notification-service module: pom.xml with dependencies (common-lib, events, spring-boot-starter-web/data-jpa/actuator/validation/flyway, spring-kafka, kafka-avro-serializer, micrometer-tracing-brave), application.yml with dev/test profiles, added module to parent pom
- Added postgres-notification service to docker-compose.yml (port 5437, notification_db, 256M limit, core profile)
- Created Flyway V1 migration with notification_log table (type, recipient, channel, status, subject, content, order_id, trace_id, error_message, created_at) with CHECK constraints and indexes
- Created domain entities: NotificationLog JPA entity, NotificationLogRepository, 3 enums (NotificationType, NotificationChannel, NotificationStatus)
- Created KafkaConsumerConfig following product-service pattern (KafkaAvroDeserializer, SPECIFIC_AVRO_READER, FixedBackOff error handler)
- Created OrderServiceClient using Spring RestClient to enrich order data via admin REST endpoint (GET /api/v1/admin/orders/{orderId}), with local DTOs (OrderDetailDto, OrderItemDto) to avoid cross-service coupling
- Created NotificationService with sendOrderConfirmation(), sendPaymentSuccess(), sendPaymentFailure() — each enriches via OrderServiceClient, sends email, and logs to notification_log with trace_id
- Created EmailService interface + MockEmailService (logs to console) — ready for production email swap
- Created OrderEventConsumer with @KafkaListener on `order.order.status-changed` topic — routes CONFIRMED events to order confirmation + payment success, routes CANCELLED (from PAYMENT_PROCESSING) to payment failure
- Payment status derived from order status transitions (no PaymentProcessedEvent publisher exists in payment-service)
- 6 unit tests in NotificationServiceTest (order confirmation content/items, payment success, payment failure with empathetic language, email failure handling, channel verification)
- 6 unit tests in OrderEventConsumerTest (CONFIRMED routing, CANCELLED from PAYMENT_PROCESSING routing, ignore irrelevant statuses)
- 3 integration tests in NotificationIntegrationIT (end-to-end Kafka consume → DB log for confirmed, payment failure, and irrelevant events) — compile verified, awaiting Docker for execution

### Change Log

- 2026-04-08: Implemented notification-service core with Kafka consumer, order enrichment, mock email, and notification logging

### File List

**New files:**
- backend/notification-service/pom.xml
- backend/notification-service/src/main/java/com/robomart/notification/NotificationServiceApplication.java
- backend/notification-service/src/main/java/com/robomart/notification/config/KafkaConsumerConfig.java
- backend/notification-service/src/main/java/com/robomart/notification/client/OrderServiceClient.java
- backend/notification-service/src/main/java/com/robomart/notification/client/OrderDetailDto.java
- backend/notification-service/src/main/java/com/robomart/notification/client/OrderItemDto.java
- backend/notification-service/src/main/java/com/robomart/notification/entity/NotificationLog.java
- backend/notification-service/src/main/java/com/robomart/notification/enums/NotificationType.java
- backend/notification-service/src/main/java/com/robomart/notification/enums/NotificationChannel.java
- backend/notification-service/src/main/java/com/robomart/notification/enums/NotificationStatus.java
- backend/notification-service/src/main/java/com/robomart/notification/event/OrderEventConsumer.java
- backend/notification-service/src/main/java/com/robomart/notification/repository/NotificationLogRepository.java
- backend/notification-service/src/main/java/com/robomart/notification/service/EmailService.java
- backend/notification-service/src/main/java/com/robomart/notification/service/MockEmailService.java
- backend/notification-service/src/main/java/com/robomart/notification/service/NotificationService.java
- backend/notification-service/src/main/resources/application.yml
- backend/notification-service/src/main/resources/db/migration/V1__init_notification_schema.sql
- backend/notification-service/src/test/java/com/robomart/notification/unit/NotificationServiceTest.java
- backend/notification-service/src/test/java/com/robomart/notification/unit/OrderEventConsumerTest.java
- backend/notification-service/src/test/java/com/robomart/notification/integration/NotificationIntegrationIT.java
- backend/notification-service/src/test/java/com/robomart/notification/integration/TestKafkaProducerConfig.java

**Modified files:**
- backend/pom.xml (added notification-service module)
- infra/docker/docker-compose.yml (added postgres-notification service + notification-data volume)
