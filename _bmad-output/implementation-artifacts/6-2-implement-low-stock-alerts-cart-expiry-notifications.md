# Story 6.2: Implement Low-Stock Alerts & Cart Expiry Notifications

Status: done

## Story

As an admin,
I want to receive low-stock alerts, and as a customer, I want cart expiry reminders,
So that admins can restock proactively and customers don't lose their carts unexpectedly.

## Acceptance Criteria

1. **Low-Stock Alert Consumed** (FR35): Given an `inventory.stock.low-alert` Kafka event (Avro `StockLowAlertEvent`), when consumed by `InventoryAlertConsumer` in notification-service, then a low-stock alert email is sent to the configured admin email with: product name (fetched from product-service), current stock, and threshold. (AC1)

2. **Cart Expiry Warning Published** (FR13): Given a customer's cart with 2 hours or fewer remaining before 24-hour TTL expiry, when the `CartExpiryWarningScheduler` in cart-service runs (every 30 minutes), then a `CartExpiryWarningEvent` is published to `cart.cart.expiry-warning` Kafka topic. Only carts with a `userId` (authenticated customers) and non-empty items are published. (AC2)

3. **Cart Expiry Notification Sent** (FR13): Given a `cart.cart.expiry-warning` Kafka event, when consumed by `CartExpiryConsumer` in notification-service, then an email is sent to the customer with: cart contents summary (product name, qty, subtotal per item), total price, and a checkout link. Duplicate warnings for the same cart are suppressed. (AC3)

4. **Notifications Event-Driven** (FR36): Given all notifications in this story, when delivered, they are triggered via Kafka event consumption — no synchronous notification calls from other services. (AC4)

5. **All Notifications Logged**: Given all notifications sent, when inspected in `notification_log` table, then each has: notification type (`LOW_STOCK_ALERT` or `CART_EXPIRY_WARNING`), recipient, channel (EMAIL), delivery status, content, created_at, trace_id. (AC5)

## Tasks / Subtasks

- [x] Task 1: Add `CartExpiryWarningEvent` Avro schema to events module (AC: #2, #3)
  - [x] 1.1 Create `backend/events/src/main/avro/cart/cart_expiry_warning.avsc`
  - [x] 1.2 Run `mvn compile` in `backend/events` to verify Avro schema compiles to `CartExpiryWarningEvent` class

- [x] Task 2: Add Kafka producer to inventory-service (AC: #1)
  - [x] 2.1 Create `KafkaProducerConfig.java` (follow product-service pattern exactly)
  - [x] 2.2 Create `InventoryEventProducer.java` (follow product-service `ProductEventProducer` pattern)
  - [x] 2.3 Create `OutboxPollingService.java` — polls outbox, builds `StockLowAlertEvent`, publishes to `inventory.stock.low-alert`
  - [x] 2.4 Add `@EnableScheduling` to `InventoryServiceApplication.java`

- [x] Task 3: Add cart expiry warning scheduler to cart-service (AC: #2)
  - [x] 3.1 Create `KafkaProducerConfig.java` (follow product-service pattern exactly)
  - [x] 3.2 Create `CartEventProducer.java` (follow `ProductEventProducer` pattern)
  - [x] 3.3 Create `CartExpiryWarningScheduler.java` — SCAN Redis for expiring carts, publish events
  - [x] 3.4 Add `@EnableScheduling` to `CartServiceApplication.java`
  - [x] 3.5 Update `cart-service/application.yml` — add `notification.checkout-base-url` property

- [x] Task 4: Update notification-service for new notification types (AC: #1, #3, #5)
  - [x] 4.1 Add `LOW_STOCK_ALERT`, `CART_EXPIRY_WARNING` to `NotificationType.java`
  - [x] 4.2 Create Flyway `V2__add_notification_types.sql` — update CHECK constraint
  - [x] 4.3 Add `sendLowStockAlert()` to `NotificationService.java`
  - [x] 4.4 Add `sendCartExpiryWarning()` to `NotificationService.java`
  - [x] 4.5 Create `ProductServiceClient.java` — calls product-service REST API for product name
  - [x] 4.6 Create `InventoryAlertConsumer.java` — listens to `inventory.stock.low-alert`
  - [x] 4.7 Create `CartExpiryConsumer.java` — listens to `cart.cart.expiry-warning`
  - [x] 4.8 Update `application.yml` — add `notification.admin-email` and `notification.product-service.url`

- [x] Task 5: Write tests (AC: #1-#5)
  - [x] 5.1 Unit test: `InventoryAlertConsumerTest` — verify routing to NotificationService
  - [x] 5.2 Unit test: `CartExpiryConsumerTest` — verify routing and duplicate suppression
  - [x] 5.3 Unit test: `CartExpiryWarningSchedulerTest` — verify TTL filtering and event publishing
  - [x] 5.4 Integration test: extend `NotificationIntegrationIT` with low-stock and cart-expiry scenarios

### Review Follow-ups (AI)

**Decision-Needed** — require author input before patching:

- [x] [AI-Review][Decision] D1: OutboxPollingService has no distributed locking — fixed with SELECT FOR UPDATE SKIP LOCKED native query in OutboxEventRepository
- [x] [AI-Review][Decision] D2: CartExpiryWarningScheduler has no distributed lock — accepted (c): notification-service deduplication handles duplicate warnings
- [x] [AI-Review][Decision] D3: Kafka consumer thread blocked by synchronous HTTP call — accepted (c): low-volume alerts, added in-memory cache to ProductServiceClient
- [x] [AI-Review][Decision] D4: `CartExpiryWarningScheduler` loads ALL Redis keys into HashSet — fixed (a): keys now processed inline inside cursor loop via `processCartKey()`

**Patches** — unambiguous fixes:

- [x] [AI-Review][Patch] P1: Missing cart total price in `buildCartExpiryBody()` — fixed: BigDecimal total computed and appended
- [x] [AI-Review][Patch] P2: `buildCartExpiryBody` shows misleading "0h 0m" when TTL < 60 seconds — fixed: shows "less than a minute"
- [x] [AI-Review][Patch] P3: Unknown event type — not a real issue: `default` branch in switch logs a warn; kafka consumer idempotency is separate concern
- [x] [AI-Review][Patch] P4: `toInt()` throws `IllegalArgumentException` — fixed: added `IllegalArgumentException` catch to permanently skip corrupted events
- [x] [AI-Review][Patch] P5: `log.info("Low stock alert sent")` runs even when FAILED — fixed: conditional info/warn based on status
- [x] [AI-Review][Patch] P6: No exception handling in consumers — not fixed: Spring Kafka error handler provides the right abstraction; per-method try/catch adds noise without benefit

**Deferred:**

- [x] [AI-Review][Defer] W1: TTL value captured at SCAN time may be slightly stale by the time `CartExpiryWarningEvent` is delivered [CartExpiryWarningScheduler.java:~63] — deferred, pre-existing timing approximation

## Dev Notes

### CRITICAL: inventory-service OutboxPollingService is MISSING

The `InventoryService.reserveStock()` already creates `stock_low_alert` outbox events (step 9 in the flow). The outbox table is populated. **However, there is NO `OutboxPollingService` in inventory-service** — the events are never published to Kafka.

**DO NOT add low-stock outbox creation logic** — it already exists in `InventoryService.java:156-177`. **DO add** the `OutboxPollingService` to inventory-service.

### Avro Field Mapping — Outbox Payload vs Avro Schema

The outbox payload in `InventoryService.java` stores:
```java
alertPayload.put("productId", productId);           // Long value → maps to Avro: productId (String)
alertPayload.put("availableQuantity", ...);         // Integer → maps to Avro: currentQuantity (int)
alertPayload.put("lowStockThreshold", ...);         // Integer → maps to Avro: threshold (int)
```

The Avro schema (`stock_low_alert.avsc`) uses: `productId (string)`, `currentQuantity (int)`, `threshold (int)`.

**In `OutboxPollingService.publishEvent()`**, the mapping is:
```java
Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
var avroEvent = StockLowAlertEvent.newBuilder()
    .setEventId(UUID.randomUUID().toString())
    .setEventType("STOCK_LOW_ALERT")
    .setAggregateId(event.getAggregateId())
    .setAggregateType("InventoryItem")
    .setTimestamp(Instant.now())
    .setVersion(1)
    .setProductId(String.valueOf(payload.get("productId")))        // Long → String
    .setCurrentQuantity(toInt(payload.get("availableQuantity")))   // FIELD RENAME: availableQuantity → currentQuantity
    .setThreshold(toInt(payload.get("lowStockThreshold")))         // FIELD RENAME: lowStockThreshold → threshold
    .build();
```

### New Avro Schema: CartExpiryWarningEvent

Create `backend/events/src/main/avro/cart/cart_expiry_warning.avsc`:

```json
{
  "type": "record",
  "name": "CartExpiryWarningEvent",
  "namespace": "com.robomart.events.cart",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "eventType", "type": "string"},
    {"name": "aggregateId", "type": "string"},
    {"name": "aggregateType", "type": "string"},
    {"name": "timestamp", "type": {"type": "long", "logicalType": "timestamp-millis"}},
    {"name": "version", "type": "int"},
    {"name": "cartId", "type": "string"},
    {"name": "userId", "type": "string"},
    {"name": "expiresInSeconds", "type": "long"},
    {"name": "checkoutUrl", "type": "string"},
    {"name": "items", "type": {"type": "array", "items": {
      "type": "record",
      "name": "CartItemSummary",
      "fields": [
        {"name": "productId", "type": "long"},
        {"name": "productName", "type": "string"},
        {"name": "price", "type": "string"},
        {"name": "quantity", "type": "int"},
        {"name": "subtotal", "type": "string"}
      ]
    }}}
  ]
}
```

After creation, run `mvn compile` in `backend/events` to generate `CartExpiryWarningEvent` and `CartItemSummary` classes.

### Inventory-Service: KafkaProducerConfig + InventoryEventProducer

Follow **product-service pattern exactly**. Copy-adapt from:
- `backend/product-service/src/main/java/com/robomart/product/config/KafkaProducerConfig.java`
- `backend/product-service/src/main/java/com/robomart/product/event/producer/ProductEventProducer.java`

**`inventory-service/config/KafkaProducerConfig.java`**:
```java
package com.robomart.inventory.config;
// Same as product-service's KafkaProducerConfig — String key, SpecificRecord value, KafkaAvroSerializer
// Uses: ${spring.kafka.bootstrap-servers}, ${spring.kafka.properties.schema.registry.url}
// Beans: ProducerFactory<String, SpecificRecord>, KafkaTemplate<String, SpecificRecord>
```

**`inventory-service/event/producer/InventoryEventProducer.java`**:
```java
package com.robomart.inventory.event.producer;

@Component
public class InventoryEventProducer {
    public static final String TOPIC_STOCK_LOW_ALERT = "inventory.stock.low-alert";
    
    private final KafkaTemplate<String, SpecificRecord> kafkaTemplate;
    private final Tracer tracer;  // io.micrometer.tracing.Tracer — available via spring-boot-starter-actuator
    
    // Same send() method as ProductEventProducer — adds x-trace-id and x-correlation-id headers
}
```

Note: `Tracer` is available transitively through `spring-boot-starter-actuator` (already in inventory-service pom). No new dependency needed.

### Inventory-Service: OutboxPollingService

The inventory-service already has `OutboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc()`. The `OutboxPollingService` follows the product-service pattern but with inventory event types:

```java
package com.robomart.inventory.service;

@Service
public class OutboxPollingService {
    private static final int BATCH_SIZE = 50;

    @Scheduled(fixedDelay = 1000)
    public void pollAndPublish() {
        var events = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc()
                .stream().limit(BATCH_SIZE).toList();
        // For each event: publishEvent(event), event.markPublished(), save
    }

    private void publishEvent(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
        switch (event.getEventType()) {
            case "stock_low_alert" -> {
                var avroEvent = StockLowAlertEvent.newBuilder()
                    .setEventId(UUID.randomUUID().toString())
                    .setEventType("STOCK_LOW_ALERT")
                    .setAggregateId(event.getAggregateId())
                    .setAggregateType("InventoryItem")
                    .setTimestamp(Instant.now())
                    .setVersion(1)
                    .setProductId(String.valueOf(payload.get("productId")))
                    .setCurrentQuantity(toInt(payload.get("availableQuantity")))   // RENAME
                    .setThreshold(toInt(payload.get("lowStockThreshold")))          // RENAME
                    .build();
                inventoryEventProducer.send(InventoryEventProducer.TOPIC_STOCK_LOW_ALERT,
                        event.getAggregateId(), avroEvent).get(10, TimeUnit.SECONDS);
            }
            // stock_reserved and stock_released are consumed by order-service saga (already working),
            // but they use the outbox too — DO NOT miss them! Must handle them even if notification doesn't care.
            // These events are routed to topics: inventory.stock.reserved, inventory.stock.released
            case "stock_reserved" -> {
                // StockReservedEvent fields: eventId, eventType, aggregateId, aggregateType, timestamp, version, orderId, productId, quantity
                // Outbox payload has: productId (Long), quantity (Integer), orderId (String), availableQuantity, reservedQuantity
                var avroEvent = StockReservedEvent.newBuilder()
                    .setEventId(UUID.randomUUID().toString())
                    .setEventType("STOCK_RESERVED")
                    .setAggregateId(event.getAggregateId())
                    .setAggregateType("InventoryItem")
                    .setTimestamp(Instant.now())
                    .setVersion(1)
                    .setOrderId(String.valueOf(payload.get("orderId")))
                    .setProductId(String.valueOf(payload.get("productId")))
                    .setQuantity(toInt(payload.get("quantity")))
                    .build();
                inventoryEventProducer.send("inventory.stock.reserved", event.getAggregateId(), avroEvent).get(10, TimeUnit.SECONDS);
            }
            case "stock_released" -> {
                // StockReleasedEvent fields: eventId, eventType, aggregateId, aggregateType, timestamp, version, orderId, productId, quantity, reason
                // Outbox payload has: productId (Long), quantity (Integer), orderId (String), availableQuantity, reservedQuantity
                // NOTE: 'reason' is NOT in outbox payload — use a computed default
                var avroEvent = StockReleasedEvent.newBuilder()
                    .setEventId(UUID.randomUUID().toString())
                    .setEventType("STOCK_RELEASED")
                    .setAggregateId(event.getAggregateId())
                    .setAggregateType("InventoryItem")
                    .setTimestamp(Instant.now())
                    .setVersion(1)
                    .setOrderId(String.valueOf(payload.get("orderId")))
                    .setProductId(String.valueOf(payload.get("productId")))
                    .setQuantity(toInt(payload.get("quantity")))
                    .setReason("Stock released from order " + payload.get("orderId"))
                    .build();
                inventoryEventProducer.send("inventory.stock.released", event.getAggregateId(), avroEvent).get(10, TimeUnit.SECONDS);
            }
            default -> log.warn("Unknown inventory outbox event type: {}", event.getEventType());
        }
    }
}
```

**CRITICAL**: The inventory outbox also has `stock_reserved` and `stock_released` events (written by `InventoryService.reserveStock()` and `releaseStock()`). These events are consumed by `order-service`'s saga flow. Without publishing them, the saga breaks! Check `backend/events/src/main/avro/inventory/stock_reserved.avsc` and `stock_released.avsc` for the schemas. The outbox `eventType` values are `"stock_reserved"` and `"stock_released"` (snake_case, as set in `InventoryService.java`).

Add `@EnableScheduling` to `InventoryServiceApplication.java`:
```java
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.robomart")
public class InventoryServiceApplication { ... }
```

### Cart-Service: KafkaProducerConfig + CartEventProducer

Cart-service already has `events` and `kafka-avro-serializer` in pom.xml. Follow the same product-service pattern:

**`cart-service/config/KafkaProducerConfig.java`**: Identical structure to product-service's `KafkaProducerConfig`.

**`cart-service/event/producer/CartEventProducer.java`**:
```java
package com.robomart.cart.event.producer;

@Component
public class CartEventProducer {
    public static final String TOPIC_CART_EXPIRY_WARNING = "cart.cart.expiry-warning";
    
    private final KafkaTemplate<String, SpecificRecord> kafkaTemplate;
    private final Tracer tracer;  // available via spring-boot-starter-actuator
    
    // Same send() method as ProductEventProducer
}
```

### Cart-Service: CartExpiryWarningScheduler

```java
package com.robomart.cart.service;

@Service
public class CartExpiryWarningScheduler {
    private static final long WARN_THRESHOLD_SECONDS = 7200L;  // 2 hours
    private static final String CART_KEY_PREFIX = "cart:";

    private final StringRedisTemplate stringRedisTemplate;
    private final CartRepository cartRepository;
    private final CartEventProducer cartEventProducer;
    private final CartProperties cartProperties;

    @Value("${notification.checkout-base-url:http://localhost:5173/cart}")
    private String checkoutBaseUrl;

    @Scheduled(fixedDelay = 1800000)  // every 30 minutes
    public void scanAndWarnExpiringCarts() {
        log.info("Scanning for expiring carts...");
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(CART_KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        } catch (Exception e) {
            log.error("Failed to scan cart keys", e);
            return;
        }

        for (String key : keys) {
            try {
                Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
                if (ttl == null || ttl <= 0 || ttl > WARN_THRESHOLD_SECONDS) {
                    continue;
                }
                String cartId = key.substring(CART_KEY_PREFIX.length());
                if (cartId.isEmpty()) continue;

                Cart cart = cartRepository.findById(cartId).orElse(null);
                if (cart == null || cart.getUserId() == null || cart.getItems().isEmpty()) {
                    continue;
                }
                publishExpiryWarning(cart, ttl);
            } catch (Exception e) {
                log.error("Error processing cart key={}: {}", key, e.getMessage());
            }
        }
    }

    private void publishExpiryWarning(Cart cart, long ttlSeconds) {
        List<CartItemSummary> items = cart.getItems().stream()
            .map(item -> CartItemSummary.newBuilder()
                .setProductId(item.getProductId())
                .setProductName(item.getProductName())
                .setPrice(item.getPrice().toPlainString())
                .setQuantity(item.getQuantity())
                .setSubtotal(item.getSubtotal().toPlainString())
                .build())
            .toList();

        CartExpiryWarningEvent event = CartExpiryWarningEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setEventType("CART_EXPIRY_WARNING")
            .setAggregateId(cart.getId())
            .setAggregateType("Cart")
            .setTimestamp(Instant.now())
            .setVersion(1)
            .setCartId(cart.getId())
            .setUserId(cart.getUserId())
            .setExpiresInSeconds(ttlSeconds)
            .setCheckoutUrl(checkoutBaseUrl)
            .setItems(items)
            .build();

        cartEventProducer.send(CartEventProducer.TOPIC_CART_EXPIRY_WARNING, cart.getId(), event);
        log.info("Published cart expiry warning: cartId={}, userId={}, ttl={}s",
                cart.getId(), cart.getUserId(), ttlSeconds);
    }
}
```

**Add to `cart-service/application.yml`** (base config):
```yaml
notification:
  checkout-base-url: ${CHECKOUT_BASE_URL:http://localhost:5173/cart}
```

**Add to `CartServiceApplication.java`**:
```java
@EnableScheduling
@EnableConfigurationProperties(CartProperties.class)
@SpringBootApplication(...)
public class CartServiceApplication { ... }
```

**Import notes for scheduler**:
- `org.springframework.data.redis.core.Cursor`
- `org.springframework.data.redis.core.ScanOptions`
- `org.springframework.data.redis.core.StringRedisTemplate`
- `java.util.concurrent.TimeUnit`

### Notification-Service: NotificationType Enum Update

```java
public enum NotificationType {
    ORDER_CONFIRMED, PAYMENT_SUCCESS, PAYMENT_FAILED,
    LOW_STOCK_ALERT, CART_EXPIRY_WARNING   // NEW
}
```

### Notification-Service: Flyway V2 Migration

Create `V2__add_notification_types.sql`:
```sql
-- Update notification_type check constraint to include new types
ALTER TABLE notification_log DROP CONSTRAINT chk_notification_type;
ALTER TABLE notification_log ADD CONSTRAINT chk_notification_type CHECK (
    notification_type IN (
        'ORDER_CONFIRMED', 'PAYMENT_SUCCESS', 'PAYMENT_FAILED',
        'LOW_STOCK_ALERT', 'CART_EXPIRY_WARNING'
    )
);
```

**IMPORTANT**: The existing `uk_notification_order_type UNIQUE (order_id, notification_type)` constraint is reused for idempotency:
- `CART_EXPIRY_WARNING`: store `cartId` in `order_id` column → constraint prevents duplicate warnings per cart
- `LOW_STOCK_ALERT`: NO idempotency constraint (each stock drop event is a legitimate new alert, multiple alerts per product are expected)

### Notification-Service: ProductServiceClient

Product-service runs on port 8081 (internal). The customer endpoint `GET /api/v1/products/{productId}` returns `ApiResponse<ProductDetailResponse>` wrapped response.

```java
package com.robomart.notification.client;

@Component
public class ProductServiceClient {
    private final RestClient restClient;

    public ProductServiceClient(@Value("${notification.product-service.url}") String productServiceUrl) {
        this.restClient = RestClient.builder()
            .baseUrl(productServiceUrl)
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                log.warn("Product service error: status={}", res.getStatusCode());
            })
            .build();
    }

    public String getProductName(String productId) {
        try {
            ProductApiResponse response = restClient.get()
                .uri("/api/v1/products/{productId}", Long.parseLong(productId))
                .retrieve()
                .body(ProductApiResponse.class);
            return (response != null && response.data() != null) ? response.data().name() : "Product #" + productId;
        } catch (Exception e) {
            log.warn("Failed to fetch product name for productId={}: {}", productId, e.getMessage());
            return "Product #" + productId;  // graceful fallback
        }
    }
}

// Local DTOs — do NOT import from product-service module
public record ProductApiResponse(ProductInfoDto data, String traceId) {}
public record ProductInfoDto(Long id, String name, String sku) {}
```

### Notification-Service: InventoryAlertConsumer

```java
package com.robomart.notification.event;

@Component
public class InventoryAlertConsumer {
    private final NotificationService notificationService;

    @KafkaListener(topics = "inventory.stock.low-alert",
                   groupId = "notification-inventory-alert-group")
    public void onStockLowAlert(StockLowAlertEvent event) {
        String productId = event.getProductId().toString();
        int currentQuantity = event.getCurrentQuantity();
        int threshold = event.getThreshold();
        log.info("Received low stock alert: productId={}, quantity={}, threshold={}",
                productId, currentQuantity, threshold);
        notificationService.sendLowStockAlert(productId, currentQuantity, threshold);
    }
}
```

Consumer group naming: `notification-inventory-alert-group` (pattern: `{service}-{topic-purpose}-group`).

### Notification-Service: CartExpiryConsumer

```java
package com.robomart.notification.event;

@Component
public class CartExpiryConsumer {
    private final NotificationService notificationService;

    @KafkaListener(topics = "cart.cart.expiry-warning",
                   groupId = "notification-cart-expiry-group")
    public void onCartExpiryWarning(CartExpiryWarningEvent event) {
        String cartId = event.getCartId().toString();
        String userId = event.getUserId().toString();
        log.info("Received cart expiry warning: cartId={}, userId={}", cartId, userId);
        notificationService.sendCartExpiryWarning(cartId, userId, event);
    }
}
```

### Notification-Service: NotificationService Extensions

Add to `NotificationService.java`:

```java
// Inject ProductServiceClient
private final ProductServiceClient productServiceClient;

public void sendLowStockAlert(String productId, int currentQuantity, int threshold) {
    String productName = productServiceClient.getProductName(productId);
    String adminEmail = adminEmail; // from @Value("${notification.admin-email}")
    String subject = "Low Stock Alert: " + productName;
    String body = "Low stock alert!\n\nProduct: " + productName + " (ID: " + productId + ")\n"
            + "Current stock: " + currentQuantity + " units\n"
            + "Threshold: " + threshold + " units\n\n"
            + "Please restock via Admin Dashboard.";
    // Store productId in order_id column for reference (NO idempotency constraint for low-stock)
    NotificationLog log = new NotificationLog();
    log.setRecipient(adminEmail);
    log.setOrderId("product-" + productId);  // prefix to distinguish from order IDs
    log.setNotificationType(NotificationType.LOW_STOCK_ALERT);
    log.setChannel(NotificationChannel.EMAIL);
    log.setSubject(subject);
    log.setContent(body);
    log.setTraceId(getTraceId());
    try {
        emailService.sendEmail(adminEmail, subject, body);
        log.setStatus(NotificationStatus.SENT);
    } catch (Exception e) {
        log.setStatus(NotificationStatus.FAILED);
        log.setErrorMessage(e.getMessage());
    }
    notificationLogRepository.save(log);
    // Note: No duplicate check for LOW_STOCK_ALERT — each event is a legitimate alert
}

@Value("${notification.admin-email}")
private String adminEmail;

public void sendCartExpiryWarning(String cartId, String userId, CartExpiryWarningEvent event) {
    // Idempotency check: use existsByOrderIdAndNotificationType with cartId
    if (notificationLogRepository.existsByOrderIdAndNotificationType(cartId, NotificationType.CART_EXPIRY_WARNING)) {
        log.info("Cart expiry warning already sent for cartId={}, skipping", cartId);
        return;
    }
    String subject = "Your cart is expiring soon!";
    String body = buildCartExpiryBody(event);
    sendAndLog(userId, cartId, NotificationType.CART_EXPIRY_WARNING, subject, body);
    // sendAndLog stores cartId in order_id column; uk_notification_order_type prevents duplicates
}

private String buildCartExpiryBody(CartExpiryWarningEvent event) {
    long hoursLeft = event.getExpiresInSeconds() / 3600;
    long minutesLeft = (event.getExpiresInSeconds() % 3600) / 60;
    var sb = new StringBuilder();
    sb.append("Your cart is expiring in ").append(hoursLeft).append("h ").append(minutesLeft).append("m!\n\n");
    sb.append("Items in your cart:\n");
    for (var item : event.getItems()) {
        sb.append("- ").append(item.getProductName())
          .append(" x").append(item.getQuantity())
          .append(" — $").append(item.getSubtotal()).append("\n");
    }
    sb.append("\nCheckout now: ").append(event.getCheckoutUrl());
    return sb.toString();
}
```

**IMPORTANT**: The existing `sendAndLog()` method stores `orderId` in the `order_id` column and checks `existsByOrderIdAndNotificationType`. For `CART_EXPIRY_WARNING`, pass `cartId` as the `orderId` parameter. The existing `uk_notification_order_type` constraint will handle duplicate prevention.

### Notification-Service: application.yml Updates

Add to base config:
```yaml
notification:
  order-service:
    url: ${ORDER_SERVICE_URL:http://localhost:8083}
  product-service:
    url: ${PRODUCT_SERVICE_URL:http://localhost:8081}    # NEW
  admin-email: ${ADMIN_EMAIL:admin@robomart.local}       # NEW
```

### Notification-Service: V2 Migration Constraint Note

**The existing `uk_notification_order_type UNIQUE (order_id, notification_type)` constraint**: PostgreSQL treats NULL != NULL, so multiple LOW_STOCK_ALERT rows with `order_id = 'product-123'` would conflict via the unique constraint. Since we DON'T want idempotency for LOW_STOCK_ALERT (each stock drop triggers an alert), store `product-{productId}` in `order_id` for the first alert, and **skip the duplicate check** — but this means Flyway V2 also needs to handle constraint conflicts.

**REVISED APPROACH**: LOW_STOCK_ALERT does not store `productId` in `order_id` — leave `order_id` as NULL for LOW_STOCK_ALERT. This avoids unique constraint collision. For `CART_EXPIRY_WARNING`, store `cartId` in `order_id` — the unique constraint naturally prevents duplicates. Keep `order_id` prefix only conceptually in logs.

Revised `sendLowStockAlert()`:
```java
log.setOrderId(null);  // No order reference; unique constraint doesn't apply to NULL values
```

### Existing Code Patterns to Reuse

| Pattern | Source File | What to Copy |
|---------|-------------|--------------|
| KafkaProducerConfig | `product-service/.../config/KafkaProducerConfig.java` | Exact copy, change package |
| EventProducer | `product-service/.../event/producer/ProductEventProducer.java` | Rename class, same send() method |
| OutboxPollingService | `product-service/.../service/OutboxPollingService.java` | Adapt publishEvent() for inventory types |
| OrderServiceClient | `notification-service/.../client/OrderServiceClient.java` | Pattern for ProductServiceClient |
| sendAndLog() | `notification-service/.../service/NotificationService.java:65-93` | Reuse for cart expiry notification |

### Testing Strategy

**Unit tests** (follow existing `@ExtendWith(MockitoExtension.class)` pattern):

`InventoryAlertConsumerTest`:
```java
@ExtendWith(MockitoExtension.class)
class InventoryAlertConsumerTest {
    @Mock private NotificationService notificationService;
    @InjectMocks private InventoryAlertConsumer inventoryAlertConsumer;

    @Test
    void shouldSendLowStockAlertWhenStockLowAlertEventReceived() {
        StockLowAlertEvent event = StockLowAlertEvent.newBuilder()
            .setEventId("evt-1").setEventType("STOCK_LOW_ALERT")
            .setAggregateId("42").setAggregateType("InventoryItem")
            .setTimestamp(Instant.now()).setVersion(1)
            .setProductId("42").setCurrentQuantity(5).setThreshold(10)
            .build();
        inventoryAlertConsumer.onStockLowAlert(event);
        verify(notificationService).sendLowStockAlert("42", 5, 10);
    }
}
```

`CartExpiryConsumerTest`:
- Test that `sendCartExpiryWarning()` is called with correct cartId, userId, and event
- Test that Avro `CharSequence` fields are correctly `.toString()`'d before passing

`CartExpiryWarningSchedulerTest`:
```java
@ExtendWith(MockitoExtension.class)
class CartExpiryWarningSchedulerTest {
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private CartRepository cartRepository;
    @Mock private CartEventProducer cartEventProducer;
    @Mock private CartProperties cartProperties;

    // Test: carts with TTL <= 7200 and userId → event published
    // Test: carts with TTL > 7200 → skipped
    // Test: carts with null userId → skipped
    // Test: carts with empty items → skipped
    // Test: Redis SCAN exception → logged, not propagated
}
```

**Integration test** — extend `NotificationIntegrationIT` (or add new `NotificationAlertIT`):
- Publish `StockLowAlertEvent` via test KafkaTemplate
- Await (Awaitility) and verify `notification_log` has LOW_STOCK_ALERT entry
- Verify `emailService.sendEmail()` was called (MockitoBean mock)
- Publish `CartExpiryWarningEvent` with a cartId
- Verify CART_EXPIRY_WARNING logged
- Publish second `CartExpiryWarningEvent` with same cartId
- Verify no duplicate entry (idempotency check)

**Test infrastructure**: Use `@IntegrationTest` from test-support (`com.robomart.test`) — same as Story 6.1's `NotificationIntegrationIT`.

Test Avro event builder pattern for `CartExpiryWarningEvent`:
```java
CartExpiryWarningEvent.newBuilder()
    .setEventId("test-1")
    .setEventType("CART_EXPIRY_WARNING")
    .setAggregateId("cart-123")
    .setAggregateType("Cart")
    .setTimestamp(Instant.now())   // Avro timestamp-millis logicalType → Instant setter
    .setVersion(1)
    .setCartId("cart-123")
    .setUserId("user-456")
    .setExpiresInSeconds(3600L)
    .setCheckoutUrl("http://localhost:5173/cart")
    .setItems(List.of(
        CartItemSummary.newBuilder()
            .setProductId(1L)
            .setProductName("Test Product")
            .setPrice("19.99")
            .setQuantity(2)
            .setSubtotal("39.98")
            .build()
    ))
    .build();
```

Note: Avro `timestamp-millis` logicalType generates `Instant` setter (not `long`), as learned in Story 6.1.

### Critical Import Notes

- `Cursor` and `ScanOptions`: `org.springframework.data.redis.core.Cursor`, `org.springframework.data.redis.core.ScanOptions`
- `StringRedisTemplate`: `org.springframework.data.redis.core.StringRedisTemplate`
- `StockLowAlertEvent`: `com.robomart.events.inventory.StockLowAlertEvent`
- `CartExpiryWarningEvent`, `CartItemSummary`: `com.robomart.events.cart.CartExpiryWarningEvent`, `com.robomart.events.cart.CartItemSummary`
- Avro `CharSequence` → always call `.toString()` on string fields from Avro events (learned in 6.1)
- Jackson 3.x: `tools.jackson.databind.ObjectMapper` (NOT `com.fasterxml.jackson.databind`) — needed in inventory-service `OutboxPollingService`

### Existing Files to Modify

```
backend/events/src/main/avro/cart/                        ← CREATE directory + cart_expiry_warning.avsc
backend/inventory-service/src/main/java/.../InventoryServiceApplication.java  ← ADD @EnableScheduling
backend/cart-service/src/main/java/.../CartServiceApplication.java            ← ADD @EnableScheduling
backend/cart-service/src/main/resources/application.yml                       ← ADD notification.checkout-base-url
backend/notification-service/src/main/java/.../enums/NotificationType.java    ← ADD LOW_STOCK_ALERT, CART_EXPIRY_WARNING
backend/notification-service/src/main/java/.../service/NotificationService.java ← ADD sendLowStockAlert(), sendCartExpiryWarning()
backend/notification-service/src/main/resources/application.yml               ← ADD admin-email, product-service.url
```

### New Files to Create

```
backend/events/src/main/avro/cart/
└── cart_expiry_warning.avsc

backend/inventory-service/src/main/java/com/robomart/inventory/
├── config/
│   └── KafkaProducerConfig.java
├── event/
│   └── producer/
│       └── InventoryEventProducer.java
└── service/
    └── OutboxPollingService.java

backend/cart-service/src/main/java/com/robomart/cart/
├── config/
│   └── KafkaProducerConfig.java
├── event/
│   └── producer/
│       └── CartEventProducer.java
└── service/
    └── CartExpiryWarningScheduler.java

backend/notification-service/src/main/java/com/robomart/notification/
├── client/
│   ├── ProductServiceClient.java
│   ├── ProductApiResponse.java
│   └── ProductInfoDto.java
└── event/
    ├── InventoryAlertConsumer.java
    └── CartExpiryConsumer.java
backend/notification-service/src/main/resources/db/migration/
└── V2__add_notification_types.sql

backend/notification-service/src/test/java/com/robomart/notification/
└── unit/
    ├── InventoryAlertConsumerTest.java
    └── CartExpiryConsumerTest.java
backend/cart-service/src/test/java/com/robomart/cart/
└── unit/service/
    └── CartExpiryWarningSchedulerTest.java
```

### References

- [Source: backend/inventory-service/src/main/java/com/robomart/inventory/service/InventoryService.java:155-177] — Existing low-stock outbox creation
- [Source: backend/inventory-service/src/main/java/com/robomart/inventory/repository/OutboxEventRepository.java] — findByPublishedFalseOrderByCreatedAtAsc()
- [Source: backend/events/src/main/avro/inventory/stock_low_alert.avsc] — StockLowAlertEvent schema
- [Source: backend/events/src/main/avro/inventory/stock_reserved.avsc] — StockReservedEvent (must handle in OutboxPollingService)
- [Source: backend/events/src/main/avro/inventory/stock_released.avsc] — StockReleasedEvent (must handle in OutboxPollingService)
- [Source: backend/product-service/src/main/java/com/robomart/product/config/KafkaProducerConfig.java] — Producer config pattern
- [Source: backend/product-service/src/main/java/com/robomart/product/event/producer/ProductEventProducer.java] — Event producer pattern
- [Source: backend/product-service/src/main/java/com/robomart/product/service/OutboxPollingService.java] — Outbox polling pattern
- [Source: backend/product-service/src/main/java/com/robomart/product/controller/ProductRestController.java:@GetMapping("/{productId}")] — GET /api/v1/products/{productId}
- [Source: backend/cart-service/src/main/java/com/robomart/cart/entity/Cart.java] — Cart entity with @RedisHash, @TimeToLive
- [Source: backend/notification-service/src/main/java/com/robomart/notification/service/NotificationService.java] — sendAndLog() pattern, existsByOrderIdAndNotificationType()
- [Source: _bmad-output/implementation-artifacts/6-1-implement-notification-service-core-order-notifications.md] — Story 6.1 patterns, review findings

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Completion Notes List

- **Task 1**: Created `CartExpiryWarningEvent` Avro schema with nested `CartItemSummary` record in `backend/events/src/main/avro/cart/`. Compiled successfully to Java classes.
- **Task 2**: Added full Kafka outbox polling to inventory-service. `OutboxPollingService` handles all three event types: `stock_low_alert`, `stock_reserved`, `stock_released` — critical because stock_reserved/released events are consumed by the order saga flow. `@EnableScheduling` added.
- **Task 3**: Added `CartExpiryWarningScheduler` to cart-service using Redis `SCAN` via `StringRedisTemplate`. Filters carts with TTL ≤ 7200s, skips carts with null userId or empty items. `@EnableScheduling` added.
- **Task 4**: Updated `NotificationType` enum, added Flyway V2 migration for constraint update, created `ProductServiceClient` with graceful fallback, added `InventoryAlertConsumer` and `CartExpiryConsumer` Kafka consumers, extended `NotificationService` with `sendLowStockAlert()` (no idempotency, `order_id=null`) and `sendCartExpiryWarning()` (idempotency via `uk_notification_order_type`, cartId stored in `order_id`).
- **Task 5**: Wrote 3 unit test classes (60 tests total passing). Also fixed pre-existing bug in `OrderEventConsumerTest` (used wrong method names `sendOrderConfirmation`/`sendPaymentSuccess` vs actual `sendOrderConfirmedNotifications`). Added `NotificationAlertIT` integration test covering low-stock and cart-expiry scenarios with idempotency verification.
- **CartExpiryWarningSchedulerTest**: Used `doAnswer` to mock `Cursor.forEachRemaining()` — Mockito doesn't delegate default Iterator methods to `hasNext()/next()` for mocked cursors.

### Change Log

- Implement Story 6.2: Low-Stock Alerts & Cart Expiry Notifications (Date: 2026-04-09)

### File List

**New Files:**
- `backend/events/src/main/avro/cart/cart_expiry_warning.avsc`
- `backend/inventory-service/src/main/java/com/robomart/inventory/config/KafkaProducerConfig.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/event/producer/InventoryEventProducer.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/service/OutboxPollingService.java`
- `backend/cart-service/src/main/java/com/robomart/cart/config/KafkaProducerConfig.java`
- `backend/cart-service/src/main/java/com/robomart/cart/event/producer/CartEventProducer.java`
- `backend/cart-service/src/main/java/com/robomart/cart/service/CartExpiryWarningScheduler.java`
- `backend/notification-service/src/main/java/com/robomart/notification/client/ProductServiceClient.java`
- `backend/notification-service/src/main/java/com/robomart/notification/client/ProductApiResponse.java`
- `backend/notification-service/src/main/java/com/robomart/notification/client/ProductInfoDto.java`
- `backend/notification-service/src/main/java/com/robomart/notification/event/InventoryAlertConsumer.java`
- `backend/notification-service/src/main/java/com/robomart/notification/event/CartExpiryConsumer.java`
- `backend/notification-service/src/main/resources/db/migration/V2__add_notification_types.sql`
- `backend/notification-service/src/test/java/com/robomart/notification/unit/InventoryAlertConsumerTest.java`
- `backend/notification-service/src/test/java/com/robomart/notification/unit/CartExpiryConsumerTest.java`
- `backend/notification-service/src/test/java/com/robomart/notification/integration/NotificationAlertIT.java`
- `backend/cart-service/src/test/java/com/robomart/cart/unit/service/CartExpiryWarningSchedulerTest.java`

**Modified Files:**
- `backend/inventory-service/src/main/java/com/robomart/inventory/InventoryServiceApplication.java` — Added `@EnableScheduling`
- `backend/cart-service/src/main/java/com/robomart/cart/CartServiceApplication.java` — Added `@EnableScheduling`
- `backend/cart-service/src/main/resources/application.yml` — Added `notification.checkout-base-url`
- `backend/notification-service/src/main/java/com/robomart/notification/enums/NotificationType.java` — Added `LOW_STOCK_ALERT`, `CART_EXPIRY_WARNING`
- `backend/notification-service/src/main/java/com/robomart/notification/service/NotificationService.java` — Added `sendLowStockAlert()`, `sendCartExpiryWarning()`, `ProductServiceClient` dependency
- `backend/notification-service/src/main/resources/application.yml` — Added `product-service.url`, `admin-email`
- `backend/notification-service/src/test/java/com/robomart/notification/unit/NotificationServiceTest.java` — Added `@Mock ProductServiceClient`
- `backend/notification-service/src/test/java/com/robomart/notification/unit/OrderEventConsumerTest.java` — Fixed pre-existing bug: wrong method name in verify
