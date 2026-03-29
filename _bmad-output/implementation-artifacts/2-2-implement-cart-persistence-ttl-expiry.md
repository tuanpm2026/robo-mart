# Story 2.2: Implement Cart Persistence & TTL Expiry

Status: done

## Story

As a customer,
I want my cart to persist across browser sessions when I'm logged in, and expire automatically after 24 hours of inactivity,
So that I don't lose my selections but stale carts are cleaned up.

## Acceptance Criteria (BDD)

### AC1: Cart Persistence by User Identifier
**Given** a cart associated with a user identifier (userId parameter via `X-User-Id` header)
**When** the same user identifier is used to retrieve the cart in a later session
**Then** cart data is still available in Redis, keyed by userId (FR11)
**Note:** Full auth integration (JWT → userId extraction) testable after Epic 3. For now, `X-User-Id` header serves as the userId source.

### AC2: Automatic TTL Expiry
**Given** a cart in Redis
**When** it has not been accessed for 24 hours (configurable TTL)
**Then** Redis automatically expires the cart key and releases all held references (FR12)

### AC3: TTL Reset on Access
**Given** a cart with TTL set
**When** the user accesses the cart via any operation (GET, PUT, POST, DELETE)
**Then** the TTL is reset to the full 24-hour window

### AC4: Configurable TTL Property
**Given** cart TTL configuration
**When** inspected in `application.yml`
**Then** TTL is configurable via `robomart.cart.ttl-minutes` property (default: 1440 = 24 hours)

## Tasks / Subtasks

### Task 1: Add CartProperties Configuration Class (AC: #4)
- [ ] 1.1 Create `CartProperties.java` with `@ConfigurationProperties(prefix = "robomart.cart")` — field: `ttlMinutes` (int, default 1440)
- [ ] 1.2 Add `@EnableConfigurationProperties(CartProperties.class)` to `CartServiceApplication.java` — explicit over scan-based discovery
- [ ] 1.3 Add `robomart.cart.ttl-minutes: 1440` to `application.yml` under the `robomart` prefix
- [ ] 1.4 TTL test override: use `@TestPropertySource(properties = "robomart.cart.ttl-minutes=1")` on integration test class (not application.yml test profile — keeps test config co-located with test)

### Task 2: Add TTL Support to Cart Entity (AC: #2, #3)
- [ ] 2.1 Add `@TimeToLive` Long field (`timeToLive`) to `Cart.java` — value in **seconds** (Spring Data Redis requirement)
- [ ] 2.2 Add `userId` (String) field with getter/setter — nullable, for tracking cart ownership. Set via `cart.setUserId(userId)` after creation.
- [ ] 2.3 **Do NOT add a separate `Cart(String id, String userId)` constructor.** Use existing `Cart(String id)` then call `setUserId()`. When `X-User-Id` is present, controller passes userId as the `id` parameter — so `cart.id == userId` for authenticated carts.
- [ ] 2.4 **Do NOT add `@Indexed` on userId** — `@Indexed` creates secondary index sets in Redis that are NOT auto-expired with TTL, causing orphaned index entries. Instead, use the userId directly as the cart key for authenticated users.

### Task 3: Update CartService for TTL and userId (AC: #1, #2, #3)
- [ ] 3.1 Inject `CartProperties` into `CartService`
- [ ] 3.2 Create private helper `refreshTtl(Cart cart)` — sets `cart.setTimeToLive(cartProperties.getTtlMinutes() * 60L)` (convert minutes → seconds)
- [ ] 3.3 Call `refreshTtl(cart)` before every `cartRepository.save(cart)` in all methods: `addItem`, `updateItemQuantity`, `removeItem`
- [ ] 3.4 **GET must also reset TTL**: In `getCart()`, after finding cart, call `refreshTtl(cart)` then `cartRepository.save(cart)` to reset the TTL in Redis. **Note:** This creates a read-then-write pattern with DEL+HMSET in Redis — same race condition as Story 2.1's deferred item (Epic 8 scope). Document in deferred-work.md.
- [ ] 3.5 **Service method signatures for GET/PUT/DELETE remain UNCHANGED** — controller resolves cartId from headers (userId or UUID) and passes it as the `cartId` parameter.
- [ ] 3.6 **Only `addItem()` gets an optional `userId` param** — signature: `addItem(String cartId, AddCartItemRequest request, String userId)`. When creating a new cart AND userId is non-null, call `cart.setUserId(userId)`. Existing carts keep their userId unchanged. This tracks ownership without changing lookup logic.

### Task 4: Update CartRestController for userId Header (AC: #1)
- [ ] 4.1 Add constant `USER_ID_HEADER = "X-User-Id"`
- [ ] 4.2 Accept `@RequestHeader(value = USER_ID_HEADER, required = false) String userId` on all endpoints
- [ ] 4.3 **Cart ID resolution logic**: When `X-User-Id` is present, use userId as cartId (overrides `X-Cart-Id`). When absent, fall back to `X-Cart-Id` / UUID generation (existing anonymous behavior).
- [ ] 4.4 Pass userId to `cartService.addItem(cartId, request)` — the service uses userId as cartId when present
- [ ] 4.5 For GET/PUT/DELETE with `X-User-Id`: use userId as the cartId for lookup

### Task 5: Add CART_EXPIRED Error Code (AC: #2)
- [ ] 5.1 Add `CART_EXPIRED` to `ErrorCode` enum in common-lib
- [ ] 5.2 **Do NOT create CartExpiredException yet** — when Redis expires a cart key, `findById()` returns empty → existing `CartNotFoundException` is thrown. `CART_EXPIRED` is reserved for future use when expiry detection (e.g., keyspace notifications) is implemented.

### Task 6: Unit Tests (AC: #1-#4)
- [ ] 6.1 Update `CartServiceTest` — test that TTL is set on cart entity before save in every mutation method
- [ ] 6.2 Add test: `shouldUseTtlFromProperties()` — verify CartProperties.ttlMinutes * 60 is set as timeToLive
- [ ] 6.3 Add test: `shouldResetTtlOnGetCart()` — verify getCart saves cart after retrieving to reset TTL
- [ ] 6.4 Add test: `shouldUseUserIdAsCartIdWhenProvided()` — verify userId is used as cart key
- [ ] 6.5 Add test: `shouldFallbackToCartIdWhenNoUserId()` — verify anonymous cart behavior unchanged
- [ ] 6.6 Add test: `shouldPreserveUserIdOnSubsequentAddItem()` — verify userId is not lost when adding more items
- [ ] 6.7 Add `CartPropertiesTest` — verify default value is 1440

### Task 7: Integration Tests (AC: #1-#4)
- [ ] 7.1 `shouldPersistCartByUserId()` — create cart with X-User-Id, retrieve with same userId, verify data intact
- [ ] 7.2 `shouldExpireCartAfterTtl()` — create cart with test TTL (1 minute), wait for expiry using `Awaitility.await().atMost(90, SECONDS)`, verify cart returns 404
- [ ] 7.3 `shouldResetTtlOnCartAccess()` — create cart, wait ~30s, access cart (GET), verify cart still exists after original TTL would have expired
- [ ] 7.4 `shouldMaintainAnonymousCartBehavior()` — verify X-Cart-Id still works without X-User-Id (backward compatibility)
- [ ] 7.5 `shouldReturnSameCartForSameUserId()` — add items across multiple requests with same X-User-Id, verify items accumulate in single cart
- [ ] 7.6 Use `@TestPropertySource(properties = "robomart.cart.ttl-minutes=1")` for TTL tests to keep test duration reasonable

## Dev Notes

### Architecture Compliance

**Cart ID Resolution (Pre-Auth):**
- `X-User-Id` header → use userId as Redis key (format: `cart:{userId}`)
- `X-Cart-Id` header → use UUID as Redis key (format: `cart:{uuid}`)
- `X-User-Id` takes precedence when both headers present
- Controller resolves cartId from headers, service methods operate on cartId — service is agnostic to userId vs UUID
- After Epic 3: API Gateway extracts userId from JWT and sets `X-User-Id` header — no Cart Service code changes needed
- Cart merge (anonymous → authenticated) is Story 3.4 scope — NOT this story
- **Pre-login cart limitation**: Users who add items anonymously then log in will NOT see their anonymous cart until Story 3.4 implements merge

**TTL Implementation via `@TimeToLive`:**
- Spring Data Redis `@TimeToLive` annotation on a `Long` field sets Redis TTL (in seconds) on `save()`
- Every `cartRepository.save(cart)` call resets the Redis EXPIRE timer
- For mutations (POST, PUT, DELETE): already call `save()` — just set `timeToLive` before saving
- For GET: must explicitly `save()` after read to refresh TTL in Redis
- When TTL expires, Redis deletes the key automatically — `findById()` returns empty

**Configuration Pattern:**
```java
@ConfigurationProperties(prefix = "robomart.cart")
public class CartProperties {
    private int ttlMinutes = 1440; // 24 hours default
    // getter, setter
}

// In CartServiceApplication.java:
@EnableConfigurationProperties(CartProperties.class)
```

```yaml
# application.yml
robomart:
  cart:
    ttl-minutes: 1440
```

**`@Indexed` Warning — DO NOT USE:**
Spring Data Redis `@Indexed` creates phantom secondary index sets (e.g., `cart:userId:{value}`) that are NOT auto-cleaned when the primary key expires via TTL. This causes orphaned phantom keys to accumulate in Redis. For userId-based lookup, use the userId directly as the cart's `@Id` key instead.

### Existing Code to Modify

**`Cart.java`** — Add fields:
- `@TimeToLive private Long timeToLive;` (seconds)
- `private String userId;` (nullable, for tracking which user owns the cart)

**`CartService.java`** — Changes:
- Inject `CartProperties`
- Add `refreshTtl()` helper
- Call `refreshTtl()` before every `save()`
- `getCart()` must save after read to reset TTL
- `addItem()` accepts optional userId parameter

**`CartRestController.java`** — Changes:
- Accept `X-User-Id` header on all endpoints
- Cart ID resolution: userId → cartId fallback logic
- Pass resolved cartId to service

**`application.yml`** — Add:
- `robomart.cart.ttl-minutes: 1440`
- Test profile override: `robomart.cart.ttl-minutes: 1`

**`ErrorCode.java`** — Add `CART_EXPIRED` (reserved for future use)

### NOT in Scope (Later Stories/Epics)

- Cart merge (anonymous → authenticated) → Story 3.4
- Cart expiry email notification (FR13) → Epic 6
- Redis caching for product data → Story 2.3
- Cart UI → Story 2.4
- Auth/JWT integration → Epic 3
- Redis keyspace notifications for expiry events → deferred

### Patterns to Follow (from Story 2.1)

**RestClient for integration tests:**
```java
RestClient restClient = RestClient.builder()
    .baseUrl("http://localhost:" + port)
    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
    .build();
```

**AssertJ assertions only:**
```java
assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
assertThat(cart.getTimeToLive()).isEqualTo(1440 * 60L);
```

**Test naming: `should{Expected}When{Condition}()`**

**ApiResponse wrapper for all responses:**
```java
return ResponseEntity.ok(new ApiResponse<>(cart, getTraceId()));
```

### Previous Story Intelligence (Story 2.1)

**Key Learnings:**
1. Cart entity uses `@RedisHash("cart")` — Redis key format is `cart:{id}`
2. `CrudRepository<Cart, String>` is the repository — no custom methods needed for findById
3. Cart ID currently via `X-Cart-Id` header (UUID). Controller generates UUID if missing.
4. `CartServiceApplication` excludes `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration`, `FlywayAutoConfiguration` — Redis-only service
5. MapStruct `CartMapper` maps Cart → CartResponse with `@Mapping(source = "id", target = "cartId")`
6. MAX_ITEM_QUANTITY = 9999 cap on quantity addition
7. `Collections.unmodifiableList()` on `getItems()` for immutability
8. Micrometer Tracer injected in controller for traceId in responses

**Review Fixes Applied in 2.1:**
- Integer overflow protection on quantity
- `@DecimalMin("0")` on BigDecimal price
- Immutable list exposure
- Price/name update on re-add same productId
- `@Min(1)` on productId
- Empty string instead of "no-trace" literal for traceId

**Deferred Issues Still Open:**
- Race condition on read-modify-write (Epic 8)
- No Redis authentication (prod concern)
- Missing Redis connection timeouts (prod concern)
- No Redis error handling / circuit breaker (Epic 8)

### Project Structure Notes

**Files to modify:**
```
backend/cart-service/src/main/java/com/robomart/cart/
├── entity/Cart.java                    # Add @TimeToLive, userId fields
├── service/CartService.java            # Inject CartProperties, add TTL refresh, userId support
├── controller/CartRestController.java  # Accept X-User-Id, cart ID resolution
backend/cart-service/src/main/resources/
└── application.yml                     # Add robomart.cart.ttl-minutes
backend/common-lib/src/main/java/com/robomart/common/logging/
└── ErrorCode.java                      # Add CART_EXPIRED
```

**Files to create:**
```
backend/cart-service/src/main/java/com/robomart/cart/config/
└── CartProperties.java                 # @ConfigurationProperties
```

**Test files to modify:**
```
backend/cart-service/src/test/java/com/robomart/cart/
├── unit/CartServiceTest.java           # Add TTL and userId tests
└── integration/CartIntegrationTest.java # Add TTL expiry and userId persistence tests
```

### References

- [Source: epics.md#Story 2.2 (lines 649-672)] — acceptance criteria, user story
- [Source: architecture.md#Cart Service] — service boundary, Redis data model, config prefix
- [Source: architecture.md#REST API Conventions] — response format, HTTP status codes
- [Source: architecture.md#Error Codes] — CART_EXPIRED code definition
- [Source: architecture.md#Testing Strategy] — test naming, AssertJ, Testcontainers
- [Source: prd.md#FR11] — cart persistence for authenticated users
- [Source: prd.md#FR12] — cart TTL expiry (default 24 hours)
- [Source: ux-design-specification.md#Cart Persistence] — "Cart survives browser close, tab switch, login/logout"
- [Source: ux-design-specification.md#Session Expiry] — "Silent refresh token rotation, cart persisted in Redis"
- [Source: 2-1-add-redis-to-infrastructure-implement-cart-service-core.md] — Story 2.1 implementation patterns, review findings, deferred items
- [Source: deferred-work.md] — ongoing deferred items (race condition, Redis auth, timeouts)

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6

### Completion Notes List

- All 7 tasks completed: CartProperties, Cart entity TTL+userId, CartService TTL refresh, controller X-User-Id, ErrorCode, unit tests, integration tests
- 23 unit tests (11 original + 9 new TTL/userId tests + 3 mapper)
- 14 integration tests (9 original + 3 userId persistence + 2 TTL expiry/refresh)
- TTL implementation via `@TimeToLive` annotation on Cart entity — Spring Data Redis auto-expires keys
- Every cart operation (GET/POST/PUT/DELETE) refreshes TTL by calling `cartRepository.save()` with updated `timeToLive`
- GET method now saves cart to reset TTL — creates read-then-write pattern (race condition acknowledged, deferred to Epic 8)
- `X-User-Id` header takes precedence over `X-Cart-Id` for cart ID resolution
- `CART_EXPIRED` error code added to common-lib (reserved for future keyspace notification implementation)
- Zero regressions: all 28 common-lib + 27 product-service tests pass

### Review Findings

- [x] [Review][Patch] TTL validation — `CartProperties.setTtlMinutes()` now rejects <= 0 values; `getTtlSeconds()` uses `(long)` cast to prevent overflow [CartProperties.java:14-19]
- [x] [Review][Patch] Defensive copy in `Cart.setItems()` — prevents external mutation of items list [Cart.java:56]
- [x] [Review][Patch] Cart ID trimming — `resolveCartId()` now trims whitespace from userId/cartId [CartRestController.java:100-106]
- [x] [Review][Defer] GET cart race condition (read-then-write) — same pattern as Story 2.1 deferred item; needs distributed locking (Epic 8 scope) [CartService.java:89-95]
- [x] [Review][Defer] No userId/cartId input validation for special characters — deferred to API Gateway (Epic 3 scope)
- [x] [Review][Defer] No max length check on userId/cartId — deferred to API Gateway (Epic 3 scope)
- [x] [Review][Defer] Slow TTL integration tests (~150s) — necessary for correctness, consider @Tag("slow") for CI optimization
- [x] [Review][Defer] No Redis error handling / circuit breaker — carried forward from Story 2.1 deferred (Epic 8 scope)

### Change Log

- 2026-03-29: Story 2.2 implemented — Cart persistence by userId + TTL expiry
- 2026-03-29: Code review patches applied — TTL validation, defensive copy, cart ID trimming

### File List

**New files:**
- backend/cart-service/src/main/java/com/robomart/cart/config/CartProperties.java

**Modified files:**
- backend/cart-service/src/main/java/com/robomart/cart/CartServiceApplication.java — added @EnableConfigurationProperties
- backend/cart-service/src/main/java/com/robomart/cart/entity/Cart.java — added @TimeToLive, userId fields
- backend/cart-service/src/main/java/com/robomart/cart/service/CartService.java — added CartProperties injection, refreshTtl(), userId support in addItem()
- backend/cart-service/src/main/java/com/robomart/cart/controller/CartRestController.java — added X-User-Id header, resolveCartId()
- backend/cart-service/src/main/resources/application.yml — added robomart.cart.ttl-minutes config
- backend/common-lib/src/main/java/com/robomart/common/logging/ErrorCode.java — added CART_EXPIRED
- backend/cart-service/src/test/java/com/robomart/cart/unit/CartServiceTest.java — added 9 TTL/userId tests
- backend/cart-service/src/test/java/com/robomart/cart/integration/CartIntegrationTest.java — added 5 userId/TTL tests
