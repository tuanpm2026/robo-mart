# Story 2.3: Implement Redis Caching with Event-Driven Invalidation

Status: done

## Story

As a customer,
I want frequently accessed product data to be cached for fast retrieval, with cache automatically invalidated when products change,
So that I always see current data without slow database queries.

## Acceptance Criteria

1. **Given** Spring Cache + Redis configured on Product Service, **When** a product detail is requested via `GET /api/v1/products/{productId}`, **Then** the result is cached in Redis with 5-minute TTL (FR64)

2. **Given** product search results, **When** the same search query is repeated within 1 minute, **Then** cached results are returned from Redis (1-minute TTL for search results) (FR64)

3. **Given** a product is updated in PostgreSQL, **When** the `product.product.updated` Kafka event is consumed, **Then** the corresponding cache entries (product detail + affected search results) are invalidated in Redis (FR65)

4. **Given** a product is deleted, **When** the `product.product.deleted` Kafka event is consumed, **Then** all cache entries for that product are invalidated (FR65)

5. **Given** Cart Service, **When** it receives a `product.product.updated` event (e.g., price change), **Then** it updates the cached price in any active cart containing that product

## Tasks / Subtasks

- [x] Task 1: Add Spring Cache + Redis dependencies and configuration to Product Service (AC: #1, #2)
  - [x]1.1 Add `spring-boot-starter-data-redis` and `spring-boot-starter-cache` to product-service `pom.xml`
  - [x]1.2 Create `CacheConfig.java` in `product-service/config/` — configure `RedisCacheManager` with two named caches: `productDetail` (5-min TTL) and `productSearch` (1-min TTL)
  - [x]1.3 Add `@EnableCaching` to `CacheConfig` or `ProductServiceApplication`
  - [x]1.4 Add Redis connection properties to `application.yml` (reuse same Redis instance as cart-service: `localhost:6379`)

- [x]Task 2: Apply `@Cacheable` to Product Service read methods (AC: #1, #2)
  - [x]2.1 Add `@Cacheable("productDetail")` to `ProductService.getProductById()` — cache key = `productId`
  - [x]2.2 Add `@Cacheable("productSearch")` to `ProductSearchService.search()` — cache key = composite of all search parameters + pagination
  - [x]2.3 Ensure cached responses serialize/deserialize correctly (ApiResponse, PagedResponse wrapping)

- [x]Task 3: Create Kafka consumer in Product Service for cache invalidation (AC: #3, #4)
  - [x]3.1 Create `ProductCacheInvalidationConsumer.java` in `product-service/event/consumer/`
  - [x]3.2 Listen to `product.product.updated` — evict `productDetail` cache entry for that productId + evict all `productSearch` entries
  - [x]3.3 Listen to `product.product.deleted` — evict `productDetail` cache entry for that productId + evict all `productSearch` entries
  - [x]3.4 Add Kafka consumer group config: `product-service-cache-invalidation-group`
  - [x]3.5 Implement idempotency guard using eventId (log duplicate, skip processing)

- [x]Task 4: Add Kafka dependencies to Cart Service and create ProductEventConsumer (AC: #5)
  - [x]4.1 Add `spring-kafka`, `kafka-avro-serializer`, and `events` module dependencies to cart-service `pom.xml`
  - [x]4.2 Add Kafka config to cart-service `application.yml` (bootstrap-servers, schema.registry.url, consumer group)
  - [x]4.3 Create `ProductEventConsumer.java` in `cart-service/event/consumer/`
  - [x]4.4 Listen to `product.product.updated` — find all active carts containing that productId, update cached price
  - [x]4.5 Consumer group: `cart-service-product.product.updated-group`

- [x]Task 5: Unit tests (AC: #1-#5)
  - [x]5.1 `ProductServiceCacheTest` — verify `getProductById` result is cached (mock repository, call twice, verify repo called once)
  - [x]5.2 `ProductSearchServiceCacheTest` — verify `search` result is cached for same params
  - [x]5.3 `ProductCacheInvalidationConsumerTest` — verify cache eviction on update/delete events
  - [x]5.4 `ProductEventConsumerTest` (cart-service) — verify price update propagation to active carts

- [x]Task 6: Integration tests (AC: #1-#5)
  - [x]6.1 `ProductCacheIT` — full flow: request product, verify cached in Redis, request again (no DB hit), update event, verify cache invalidated
  - [x]6.2 `CartPriceUpdateIT` — publish product.updated event with new price, verify cart items updated

- [x]Task 7: Verify all existing tests pass (no regressions)
  - [x]7.1 Run all product-service tests (unit + integration)
  - [x]7.2 Run all cart-service tests (unit + integration)
  - [x]7.3 Run all common-lib tests

## Dev Notes

### Critical Architecture Constraints

**Spring Cache + Redis (NOT Spring Data Redis repositories for caching)**
- Use `@Cacheable`, `@CacheEvict`, `@CachePut` annotations from Spring Cache abstraction
- Backend: `RedisCacheManager` with Lettuce client (bundled with Spring Boot 4.0.4)
- DO NOT use `@RedisHash` for caching — that's for primary data storage (as used in Cart entity)
- Cart Service uses `spring-boot-starter-data-redis` for `@RedisHash` entities; Product Service uses `spring-boot-starter-cache` + `spring-boot-starter-data-redis` for `@Cacheable`

**Kafka Event Infrastructure Already Exists**
- Avro schemas: `events/src/main/avro/product/product_updated.avsc`, `product_deleted.avsc`
- Generated classes: `com.robomart.events.product.ProductUpdatedEvent`, `ProductDeletedEvent`
- Event fields for `ProductUpdatedEvent`: `eventId`, `eventType`, `aggregateId`, `aggregateType`, `timestamp`, `version`, `productId` (long), `sku`, `name`, `description`, `price` (String/BigDecimal), `categoryId`, `categoryName`, `brand`, `rating`, `stockQuantity`
- Event fields for `ProductDeletedEvent`: `eventId`, `eventType`, `aggregateId`, `aggregateType`, `timestamp`, `version`, `productId` (long), `sku`
- `ProductEventProducer` exists at `product-service/event/producer/ProductEventProducer.java` — already defines topic constants: `TOPIC_PRODUCT_UPDATED`, `TOPIC_PRODUCT_DELETED`
- Topic naming: `product.product.updated`, `product.product.deleted`
- Consumer group naming: `{consuming-service}-{topic}-group`

**ProductService is Currently Read-Only**
- `ProductService.java` only has `getProducts()` and `getProductById()` — no CUD operations
- Admin product CRUD is Epic 5 scope — product update/delete events are produced via the Outbox pattern when admin operations occur
- For testing cache invalidation, publish test events directly to Kafka topics

**Cart Service Has NO Kafka Dependencies Yet**
- Must add: `spring-kafka`, `io.confluent:kafka-avro-serializer`, `com.robomart:events`
- Must add Kafka config to `application.yml`
- Must add `CartServiceApplication` does NOT need to exclude any Kafka autoconfiguration

### CacheConfig Implementation Guide

```java
// product-service/config/CacheConfig.java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration productDetailConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        RedisCacheConfiguration productSearchConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(1))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration("productDetail", productDetailConfig)
                .withCacheConfiguration("productSearch", productSearchConfig)
                .build();
    }
}
```

**Jackson 3.x Serialization Warning**: Spring Boot 4.0.4 bundles Jackson 3.x (`tools.jackson.databind`). The `GenericJackson2JsonRedisSerializer` uses Jackson for serialization. Verify that the serializer works correctly with the project's Jackson configuration (NON_NULL, ISO dates). If issues arise, configure a custom `ObjectMapper` for the serializer. The class may need to be from `org.springframework.data.redis.serializer` — check Spring Data Redis compatibility with Jackson 3.x.

### Search Cache Key Strategy

`ProductSearchService.search()` takes `ProductSearchRequest` (record with `keyword`, `categoryId`, `brand`, `minPrice`, `maxPrice`, `minRating`) and `Pageable`. The cache key must be a composite of ALL parameters:

```java
@Cacheable(value = "productSearch",
           key = "T(java.util.Objects).hash(#request.keyword(), #request.categoryId(), " +
                 "#request.brand(), #request.minPrice(), #request.maxPrice(), " +
                 "#request.minRating(), #pageable.pageNumber, #pageable.pageSize)")
```

Alternative: use a custom `KeyGenerator` bean if SpEL becomes too complex.

### Cache Invalidation Strategy

- On `product.product.updated`: evict specific `productDetail::{productId}` + evict ALL `productSearch` entries (search results may contain the updated product)
- On `product.product.deleted`: same eviction pattern
- Use `@CacheEvict(value = "productSearch", allEntries = true)` for search cache — no way to know which search queries contain the updated product
- For `productDetail`, use programmatic eviction: `cacheManager.getCache("productDetail").evict(productId)`

```java
// product-service/event/consumer/ProductCacheInvalidationConsumer.java
@Component
public class ProductCacheInvalidationConsumer {
    private final CacheManager cacheManager;

    @KafkaListener(topics = "product.product.updated",
                   groupId = "product-service-cache-invalidation-group")
    public void onProductUpdated(ProductUpdatedEvent event) {
        evictProductCaches(event.getProductId());
    }

    @KafkaListener(topics = "product.product.deleted",
                   groupId = "product-service-cache-invalidation-group")
    public void onProductDeleted(ProductDeletedEvent event) {
        evictProductCaches(event.getProductId());
    }

    private void evictProductCaches(long productId) {
        Cache productDetailCache = cacheManager.getCache("productDetail");
        if (productDetailCache != null) {
            productDetailCache.evict(productId);
        }
        Cache productSearchCache = cacheManager.getCache("productSearch");
        if (productSearchCache != null) {
            productSearchCache.clear(); // Evict all search entries
        }
    }
}
```

### Cart Price Update Pattern

```java
// cart-service/event/consumer/ProductEventConsumer.java
@Component
public class ProductEventConsumer {
    private final CartRepository cartRepository;

    @KafkaListener(topics = "product.product.updated",
                   groupId = "cart-service-product.product.updated-group")
    public void onProductUpdated(ProductUpdatedEvent event) {
        // Scan active carts in Redis for items with matching productId
        // Update price to event.getPrice()
        // This is a best-effort update — eventual consistency within 30s (NFR32)
    }
}
```

**Challenge**: `CartRepository` extends `CrudRepository<Cart, String>` — `findAll()` scans ALL carts. For MVP, this is acceptable (low cart count). For scale, consider a secondary index or event-sourced approach (Epic 8).

### Kafka Consumer Configuration for Cart Service

Cart service needs Kafka consumer config added to `application.yml`:

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:29092
    consumer:
      group-id: cart-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
    properties:
      schema.registry.url: http://localhost:8085
      specific.avro.reader: true
```

### Testcontainers Config Already Available

- `RedisContainerConfig` — singleton Redis container with `DynamicPropertyRegistrar`
- `KafkaContainerConfig` — `ConfluentKafkaContainer` with mock schema registry (`mock://test-schema-registry`)
- Both follow `@TestConfiguration(proxyBeanMethods = false)` + static singleton pattern
- Import both in integration tests: `@Import({RedisContainerConfig.class, KafkaContainerConfig.class})`

### Existing Test Patterns (from Story 2.2)

- Test naming: `should{Expected}When{Condition}()`
- AssertJ assertions only
- `@IntegrationTest` annotation from test-support
- RestClient (NOT TestRestTemplate): `RestClient.builder().baseUrl(...).defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build()`
- `@TestPropertySource` for test-specific config overrides

### Performance Targets

- API response p95 < 200ms for cached product detail (NFR1)
- Search response p95 < 500ms (NFR2) — caching improves this significantly
- Kafka event processing lag < 5s (NFR7)
- Eventual consistency within 30s window (NFR32)

### Project Structure Notes

**New files to create:**

```
product-service/
  src/main/java/com/robomart/product/
    config/
      CacheConfig.java                          # NEW: RedisCacheManager with named caches
    event/
      consumer/
        ProductCacheInvalidationConsumer.java    # NEW: Kafka consumer for cache eviction
  src/test/java/com/robomart/product/
    unit/
      service/
        ProductServiceCacheTest.java            # NEW: Cache behavior unit tests
        ProductSearchServiceCacheTest.java      # NEW: Search cache unit tests
      event/
        ProductCacheInvalidationConsumerTest.java # NEW: Consumer unit tests
    integration/
      cache/
        ProductCacheIT.java                     # NEW: Full cache flow integration test

cart-service/
  src/main/java/com/robomart/cart/
    event/
      consumer/
        ProductEventConsumer.java               # NEW: Price update consumer
  src/test/java/com/robomart/cart/
    unit/
      event/
        ProductEventConsumerTest.java           # NEW: Consumer unit tests
    integration/
      event/
        CartPriceUpdateIT.java                  # NEW: Price update integration test
```

**Files to modify:**

```
product-service/
  pom.xml                                       # ADD: spring-boot-starter-data-redis, spring-boot-starter-cache
  src/main/java/.../service/ProductService.java # ADD: @Cacheable on getProductById()
  src/main/java/.../service/ProductSearchService.java # ADD: @Cacheable on search()
  src/main/resources/application.yml            # ADD: spring.data.redis config, spring.cache config

cart-service/
  pom.xml                                       # ADD: spring-kafka, kafka-avro-serializer, events
  src/main/resources/application.yml            # ADD: spring.kafka consumer config
```

### Existing Files — DO NOT Recreate

- `RedisContainerConfig.java` — already in test-support
- `KafkaContainerConfig.java` — already in test-support
- `ProductEventProducer.java` — already in product-service (defines topic constants)
- Avro schemas — already in events module (product_updated.avsc, product_deleted.avsc)
- `@IntegrationTest` annotation — already in test-support
- `Cart.java` entity — already has items with `productId` and `price` fields
- `CartRepository.java` — already extends `CrudRepository<Cart, String>`

### Deferred Items (from previous stories — still open)

- Race condition on read-modify-write in Cart Service (Epic 8 scope)
- No Redis authentication for production (infrastructure concern)
- Missing Redis connection timeouts and error handling (Epic 8 scope)
- No circuit breaker on Redis operations (Epic 8 scope)
- `@Indexed` is dangerous with TTL — do NOT use in cart entities

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Epic 2, Story 2.3 (lines 673-699)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Data Architecture / Caching (line 330)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Cache Invalidation Flow (lines 1827-1834)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Kafka Event Naming (lines 583-591)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Kafka Topic Mapping (lines 1783-1797)]
- [Source: _bmad-output/planning-artifacts/prd.md — FR64, FR65]
- [Source: _bmad-output/planning-artifacts/prd.md — NFR1, NFR2, NFR7, NFR32, NFR33]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md — Micro-Emotions Table, Search-to-Purchase Flow]
- [Source: _bmad-output/implementation-artifacts/2-2-implement-cart-persistence-ttl-expiry.md — Dev Notes, Review Feedback]

### Review Findings

- [x] [Review][Patch] Unbounded `processedEventIds` memory leak — ConcurrentHashMap set grows forever, remove since cache eviction is idempotent [ProductCacheInvalidationConsumer.java:23]
- [x] [Review][Patch] BigDecimal price parsing throws on malformed input — add try-catch with error logging [ProductEventConsumer.java:32]
- [x] [Review][Patch] Search cache key uses `Objects.hash()` (32-bit collision risk), missing `sort` param, uses unclamped `pageSize` — switch to composite string key with clamped values [ProductSearchService.java:37-40]
- [x] [Review][Patch] Permissive polymorphic type validator `allowIfBaseType(Object.class)` allows arbitrary class deserialization — restrict to `com.robomart.*` + `java.*` [CacheConfig.java:28]
- [x] [Review][Patch] Cached `traceId` in `ApiResponse` wrapper becomes stale — cache only DTO, wrap with `ApiResponse` after cache retrieval [ProductService.java:66, ProductSearchService.java:37]
- [x] [Review][Patch] No `CacheErrorHandler` — Redis failure propagates as 500 to clients instead of falling through to DB [CacheConfig.java]
- [x] [Review][Patch] Missing unit tests `ProductServiceCacheTest` and `ProductSearchServiceCacheTest` per spec Tasks 5.1, 5.2
- [x] [Review][Patch] `shouldClearSearchCacheWhenProductUpdated` test has no assertion — test is a no-op [ProductCacheIT.java:158-191]
- [x] [Review][Patch] Unused imports `Duration`, `TimeUnit` in ProductCacheIT [ProductCacheIT.java:3-4]
- [x] [Review][Defer] `findAll()` full cart scan on every product update [ProductEventConsumer.java:39] — deferred, accepted MVP tech debt (Epic 8)
- [x] [Review][Defer] Cart read-modify-write race condition during event processing [ProductEventConsumer.java:39-49] — deferred, Epic 8 scope
- [x] [Review][Defer] No Dead Letter Topic (DLT) for failed Kafka messages [KafkaConsumerConfig.java:48] — deferred, infrastructure concern
- [x] [Review][Defer] No exception handling around individual `cartRepository.save()` [ProductEventConsumer.java:45] — deferred, retry via error handler adequate for MVP
- [x] [Review][Defer] No atomicity guarantee on multi-cart update [ProductEventConsumer.java:39-49] — deferred, Redis limitation
- [x] [Review][Defer] Race condition between cache population and invalidation — deferred, bounded by 5-min TTL
- [x] [Review][Defer] Cart TTL not explicitly reset after Kafka price update [ProductEventConsumer.java:45] — deferred, minor behavioral concern
- [x] [Review][Defer] `AUTO_OFFSET_RESET=earliest` replays historical events on first consumer group creation — deferred, one-time first-deploy concern

## Dev Agent Record

### Agent Model Used
Claude Opus 4.6 (claude-opus-4-6)

### Debug Log References
- `GenericJackson2JsonRedisSerializer` (Jackson 2.x) incompatible with Jackson 3.x in Spring Boot 4.0.4 — `java.time.Instant` serialization fails with `InvalidDefinitionException`
- Fix: switched to `GenericJacksonJsonRedisSerializer` (Jackson 3.x) from Spring Data Redis 4.0.4, using builder API with `enableDefaultTyping()`
- Avro `timestamp-millis` logical type generates `java.time.Instant` in Java (not `long`) — all test event builders updated

### Completion Notes List
- All 7 tasks completed, all 9 review patches applied
- Unit tests: 37 product-service (including 3 ProductServiceCacheTest + 2 ProductSearchServiceCacheTest + 5 ProductCacheInvalidationConsumerTest), all pass
- Integration tests: 42 product-service (including 7 ProductCacheIT), all pass
- Cart-service: 42 tests (unit + integration), all pass
- Review: 9 patches applied, 8 items deferred, 13 dismissed

### File List

**New files:**
- `backend/product-service/src/main/java/com/robomart/product/config/CacheConfig.java`
- `backend/product-service/src/main/java/com/robomart/product/event/consumer/ProductCacheInvalidationConsumer.java`
- `backend/cart-service/src/main/java/com/robomart/cart/config/KafkaConsumerConfig.java`
- `backend/cart-service/src/main/java/com/robomart/cart/event/consumer/ProductEventConsumer.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/event/ProductCacheInvalidationConsumerTest.java`
- `backend/product-service/src/test/java/com/robomart/product/integration/cache/ProductCacheIT.java`
- `backend/cart-service/src/test/java/com/robomart/cart/unit/event/ProductEventConsumerTest.java`
- `backend/cart-service/src/test/java/com/robomart/cart/integration/event/CartPriceUpdateIT.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/service/ProductServiceCacheTest.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/service/ProductSearchServiceCacheTest.java`

**Modified files:**
- `backend/product-service/pom.xml` — added `spring-boot-starter-data-redis`, `spring-boot-starter-cache`
- `backend/product-service/src/main/java/com/robomart/product/service/ProductService.java` — added `@Cacheable("productDetail")`
- `backend/product-service/src/main/java/com/robomart/product/service/ProductSearchService.java` — added `@Cacheable("productSearch")`
- `backend/product-service/src/main/resources/application.yml` — added Redis connection config
- `backend/cart-service/pom.xml` — added `spring-kafka`, `kafka-avro-serializer`, `events` module
- `backend/cart-service/src/main/resources/application.yml` — added Kafka consumer config
