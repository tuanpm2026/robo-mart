# Story 3.4: Implement Anonymous Cart Merge on Login

Status: done

## Story

As a customer,
I want my anonymous cart to be preserved and merged with my account when I log in,
So that I don't lose items I added before logging in.

## Acceptance Criteria (BDD)

1. **Given** a customer browsing without login **When** they add items to cart **Then** an anonymous cart is created in Redis with a session-based identifier

2. **Given** an anonymous cart with 3 items **When** the customer logs in (via any method: email, Google, GitHub) **Then** the anonymous cart items are merged into the authenticated user's cart via CartMergeService

3. **Given** the authenticated user already has a cart with 2 items **When** anonymous cart is merged **Then** items are combined — duplicate products have quantities summed, unique products are added, and the anonymous cart is deleted from Redis

4. **Given** the Customer Website login flow **When** login completes and cart merge happens **Then** the Cart icon Badge updates to reflect the merged total, and the user is redirected to their previous page (checkout or browsing)

## References

- [Source: epics.md#Epic-3, Story 3.4, lines 839-861]
- [Source: prd.md#Journey-6 — Registration & Auth Flow, lines 256-275]
- [Source: prd.md#FR13 — Cart merge on login]
- [Source: prd.md#FR37-FR42 — User Identity & Access]
- [Source: architecture.md#Cart-Service-Structure, lines 1241-1274]
- [Source: ux-design-specification.md#Journey-5 — Registration & Auth Flow, lines 1022-1060]
- [Source: ux-design-specification.md#Checkout-Flow, lines 581-587]

## Tasks / Subtasks

### Task 1: Backend — CartMergeService (AC: #2, #3)

- [x] 1.1 Create `CartMergeService.java` in `cart-service/src/main/java/com/robomart/cart/service/`
- [x] 1.2 Implement `mergeCart(String anonymousCartId, String authenticatedUserId)` method:
  - Fetch anonymous cart by `anonymousCartId` from `CartRepository`
  - Fetch or create authenticated user's cart by `authenticatedUserId`
  - For each anonymous item: if product exists in auth cart → sum quantities (cap at 9999); else → add item
  - Set `userId` field on merged cart to `authenticatedUserId`
  - Refresh TTL on merged cart
  - Delete anonymous cart from Redis
  - Return merged `CartResponse`
- [x] 1.3 Handle edge cases:
  - Anonymous cart not found → return authenticated user's cart as-is (no error — cart may have expired or already been merged)
  - Anonymous cart empty → delete it, return authenticated cart
  - Idempotency: if `anonymousCartId == authenticatedUserId` → no-op, return cart

### Task 2: Backend — Cart Merge REST Endpoint (AC: #2)

- [x] 2.1 Add `POST /api/v1/cart/merge` endpoint to `CartRestController.java`
  - Request body: `MergeCartRequest { anonymousCartId: String }` (required, @NotBlank)
  - Authenticated user ID from `X-User-Id` header (set by `UserIdRelayFilter` from JWT sub)
  - Returns `ApiResponse<CartResponse>` with merged cart
- [x] 2.2 Create `MergeCartRequest.java` DTO in `cart-service/src/main/java/com/robomart/cart/dto/`
- [x] 2.3 Validate: reject if `anonymousCartId` equals `userId` (self-merge is no-op)

### Task 3: Backend — API Gateway Route Update (AC: #2)

- [x] 3.1 Update `GatewaySecurityConfig.java`: add `/api/v1/cart/merge` as `.authenticated()` (must have valid JWT)
  - Keep existing `/api/v1/cart/**` as `permitAll()` for browse/add
  - Order matters: specific path `/api/v1/cart/merge` BEFORE wildcard `/api/v1/cart/**`

### Task 4: Backend — Unit Tests (AC: #2, #3)

- [x] 4.1 Create `CartMergeServiceTest.java` in `cart-service/src/test/java/com/robomart/cart/unit/service/`
  - `shouldMergeAnonymousCartIntoAuthenticatedCartWhenBothExist()`
  - `shouldSumQuantitiesWhenDuplicateProductExists()`
  - `shouldAddUniqueProductsWhenNoDuplicates()`
  - `shouldDeleteAnonymousCartAfterSuccessfulMerge()`
  - `shouldReturnAuthenticatedCartWhenAnonymousCartNotFound()`
  - `shouldReturnAuthenticatedCartWhenAnonymousCartEmpty()`
  - `shouldNoOpWhenAnonymousIdEqualsAuthenticatedId()`
  - `shouldCapQuantityAt9999WhenMergingDuplicates()`
  - `shouldRefreshTtlOnMergedCart()`
  - `shouldCreateNewCartForAuthenticatedUserWhenNoneExists()`

### Task 5: Backend — Integration Tests (AC: #2, #3)

- [x] 5.1 Added merge integration tests to `CartIntegrationTest.java` (existing file, 4 new tests)
  - Test full merge flow with real Redis (Testcontainers)
  - Verify anonymous cart is deleted from Redis after merge
  - Verify merged cart has correct items, quantities, and TTL
  - Test concurrent merge calls (idempotency)

### Task 6: Frontend — Cart Merge on Login Callback (AC: #4)

- [x] 6.1 Add `mergeCart(anonymousCartId: string)` to `cartApi.ts`
  - `POST /api/v1/cart/merge` with `{ anonymousCartId }` body
- [x] 6.2 Add `mergeAnonymousCart()` action to `useCartStore.ts`
  - Read anonymous ID from `localStorage('robomart-user-id')`
  - Call `cartApi.mergeCart(anonymousId)`
  - Update store state with merged cart response
  - Clear anonymous ID from localStorage after successful merge
- [x] 6.3 Hook merge into login flow in `useAuthStore.ts` → `handleCallback()`:
  - After successful OIDC callback and token storage
  - Before redirect to saved path
  - Call `useCartStore().mergeAnonymousCart()`
  - Merge failure should NOT block login — catch and log error, proceed with redirect
- [x] 6.4 Also hook merge into `initAuth()` for page refresh after login:
  - If user is authenticated AND localStorage still has anonymous ID → trigger merge
  - This handles edge case where callback completed but merge failed/was interrupted

### Task 7: Frontend — Cart Badge Update (AC: #4)

- [x] 7.1 Cart badge already uses `useCartStore.totalItems` (reactive computed) — verified auto-updates after merge (items.value reassignment triggers reactivity)
- [x] 7.2 If cart drawer is open during merge, items should reactively update (Pinia reactive refs ensure this)

### Task 8: Frontend Tests (AC: #4)

- [x] 8.1 Test `useCartStore.mergeAnonymousCart()`:
  - `shouldCallMergeApiWithAnonymousIdFromLocalStorage()`
  - `shouldUpdateStoreWithMergedCartResponse()`
  - `shouldClearLocalStorageAfterSuccessfulMerge()`
  - `shouldNotCallMergeWhenNoAnonymousIdExists()`
  - `shouldHandleMergeFailureGracefully()`
- [x] 8.2 Test auth callback merge integration:
  - `shouldMergeCartDuringLoginCallback()`
  - `shouldProceedWithRedirectEvenIfMergeFails()`

### Review Findings

- [x] [Review][Patch] Stale `cachedAnonymousId` in `client.ts` not cleared after merge — added `clearAnonymousIdCache()` export, called after merge [`client.ts`]
- [x] [Review][Patch] Missing `@Size(max=128)` validation on `anonymousCartId` in MergeCartRequest — added `@Size(max=128)` [`MergeCartRequest.java`]
- [x] [Review][Patch] Redundant `setUserId()` call on auth cart — removed unconditional call after merge loop [`CartMergeService.java`]
- [x] [Review][Patch] Missing test for `initAuth()` fallback merge path — added 3 tests (merge on init, skip when no ID, proceed on failure) [`useAuthStore.spec.ts`]
- [x] [Review][Patch] Integration test uses fragile string matching — replaced with JsonNode typed assertions [`CartIntegrationTest.java`]
- [x] [Review][Defer] Race condition: non-atomic read-modify-write in CartMergeService (no Redis transaction/lock) — deferred, pre-existing architectural decision acknowledged in spec
- [x] [Review][Defer] X-User-Id trusted without ownership validation on anonymousCartId — deferred, pre-existing trust model (gateway enforces auth)
- [x] [Review][Defer] No upper bound on cart item count during merge — deferred, pre-existing (addToCart has same issue)
- [x] [Review][Defer] Missing MissingRequestHeaderException handler returns 500 instead of 400 — deferred, pre-existing (affects all endpoints)

## Dev Notes

### Critical: Existing Code — DO NOT Recreate

These components already exist and must be extended, not recreated:

| Component | Path | What to Reuse |
|-----------|------|---------------|
| CartService | `backend/cart-service/src/main/java/com/robomart/cart/service/CartService.java` | Cart CRUD, `addItem()` handles duplicate merging (sums quantities, caps at 9999) |
| Cart entity | `backend/cart-service/src/main/java/com/robomart/cart/entity/Cart.java` | `addItem()`, `findItem()`, `getTotalItems()`, `getTotalPrice()`, TTL field |
| CartRestController | `backend/cart-service/src/main/java/com/robomart/cart/controller/CartRestController.java` | `resolveCartId()` pattern, existing header extraction |
| CartRepository | `backend/cart-service/src/main/java/com/robomart/cart/repository/CartRepository.java` | `CrudRepository<Cart, String>` — findById, save, deleteById |
| CartMapper | `backend/cart-service/src/main/java/com/robomart/cart/mapper/CartMapper.java` | `toCartResponse()` for DTO mapping |
| CartProperties | `backend/cart-service/src/main/java/com/robomart/cart/config/CartProperties.java` | `getTtlSeconds()` for TTL refresh |
| UserIdRelayFilter | `backend/api-gateway/src/main/java/com/robomart/gateway/filter/UserIdRelayFilter.java` | Auto-sets X-User-Id from JWT sub for authenticated requests |
| useCartStore | `frontend/customer-website/src/stores/useCartStore.ts` | Optimistic update pattern, `fetchCart()`, `$reset()` |
| useAuthStore | `frontend/customer-website/src/stores/useAuthStore.ts` | `handleCallback()`, `initAuth()` — hook merge here |
| cartApi | `frontend/customer-website/src/api/cartApi.ts` | Existing API functions, add `mergeCart()` |
| client.ts | `frontend/customer-website/src/api/client.ts` | `ANONYMOUS_USER_ID_KEY = 'robomart-user-id'` — use this constant |

### Critical: Cart Entity Merge Logic

`Cart.addItem(CartItem)` already handles duplicate detection and quantity summing:
```java
// From Cart.java — reuse this for merge, don't reinvent
public void addItem(CartItem newItem) {
    findItem(newItem.getProductId()).ifPresentOrElse(
        existing -> {
            int newQty = Math.min(existing.getQuantity() + newItem.getQuantity(), 9999);
            existing.setQuantity(newQty);
            existing.setPrice(newItem.getPrice());
            existing.setProductName(newItem.getProductName());
        },
        () -> items.add(newItem)
    );
}
```

**CartMergeService implementation should iterate anonymous cart items and call `authCart.addItem(item)` for each** — this reuses the existing duplicate/quantity logic.

### Critical: Anonymous User ID Flow

1. Frontend generates UUID → stored in `localStorage('robomart-user-id')` (key constant: `ANONYMOUS_USER_ID_KEY` in client.ts)
2. Sent as `X-User-Id` header on every request (when not authenticated)
3. Cart Service uses this as cart key in Redis: `cart:{anonymousUUID}`
4. On login: `UserIdRelayFilter` overwrites `X-User-Id` with JWT `sub` claim
5. **Story 3.4 merge**: Frontend reads localStorage value BEFORE it becomes irrelevant, sends it in merge request body
6. After successful merge: clear localStorage anonymous ID

### Critical: API Gateway Security Rule Ordering

Spring Security evaluates matchers in order. The merge endpoint MUST be declared BEFORE the cart wildcard:
```java
.pathMatchers(HttpMethod.POST, "/api/v1/cart/merge").authenticated()
.pathMatchers("/api/v1/cart/**").permitAll()
```

If reversed, `/api/v1/cart/merge` matches the wildcard first → permitAll → no JWT required → X-User-Id not set by UserIdRelayFilter → merge fails.

### Critical: Merge Timing in Frontend

The merge MUST happen:
1. **After** OIDC callback processes tokens (so JWT is available for authenticated API call)
2. **Before** redirect to saved path (so cart badge is updated before user sees the page)
3. **Non-blocking**: if merge fails, log error and continue with redirect — don't break login

```typescript
// In useAuthStore.handleCallback():
async handleCallback(): Promise<string> {
  const user = await authService.loginCallback()
  // ... map user, set tokens ...

  // Merge anonymous cart (non-blocking)
  try {
    await useCartStore().mergeAnonymousCart()
  } catch (e) {
    console.error('Cart merge failed:', e)
  }

  return authService.consumeRedirectPath() || '/'
}
```

### Critical: initAuth() Fallback Merge

Page refresh after login may leave an unmerged localStorage ID (if merge failed during callback). Handle this:
```typescript
// In useAuthStore.initAuth():
if (isAuthenticated && localStorage.getItem('robomart-user-id')) {
  await useCartStore().mergeAnonymousCart() // retry merge
}
```

### API Response Format

Follow existing pattern from CartRestController:
```java
@PostMapping("/merge")
public ApiResponse<CartResponse> mergeCart(
    @RequestHeader(value = "X-User-Id") String userId,
    @Valid @RequestBody MergeCartRequest request) {
    CartResponse response = cartMergeService.mergeCart(request.getAnonymousCartId(), userId);
    return ApiResponse.success(response);
}
```

### Error Codes

Add to error handling if needed:
- `CART_MERGE_SELF_MERGE` — anonymousCartId equals userId (return current cart, 200)
- No new error codes needed for "anonymous cart not found" — this is a graceful no-op

### Testing Patterns

**Backend unit tests**: Follow `CartServiceTest.java` pattern with `@ExtendWith(MockitoExtension.class)`, mock `CartRepository`

**Backend integration tests**: Follow `CartIntegrationTest.java` pattern with `@IntegrationTest`, `RestClient`, `@LocalServerPort`, real Redis via Testcontainers

**Frontend tests**: Follow `useCartStore.spec.ts` pattern with `vi.mock()` for API, `setActivePinia(createPinia())`

**Test naming**: `should{Expected}When{Condition}()`

**Assertions**: AssertJ for backend, Vitest `expect()` for frontend

**Test data**: Use `TestData` builders for backend entities

### Project Structure Notes

New files to create:
```
backend/cart-service/src/main/java/com/robomart/cart/
├── service/CartMergeService.java          # NEW
├── dto/MergeCartRequest.java              # NEW
└── (existing files modified: CartRestController.java)

backend/cart-service/src/test/java/com/robomart/cart/
├── unit/service/CartMergeServiceTest.java # NEW
└── integration/CartMergeIntegrationTest.java # NEW (or add to existing)

backend/api-gateway/src/main/java/com/robomart/gateway/
└── config/GatewaySecurityConfig.java      # MODIFY (add merge auth rule)

frontend/customer-website/src/
├── api/cartApi.ts                         # MODIFY (add mergeCart)
├── stores/useCartStore.ts                 # MODIFY (add mergeAnonymousCart)
└── stores/useAuthStore.ts                 # MODIFY (hook merge into callback/init)
```

### Previous Story Intelligence

**From Story 3.3 (Review):**
- UserIdRelayFilter null guard added — `jwt.getSubject()` may return null, handled with null check
- Anti-spoofing: authenticated requests always overwrite X-User-Id with JWT sub
- Header validation: max 128 chars, no control chars for anonymous IDs

**From Story 3.2 (Auth):**
- `authService.consumeRedirectPath()` returns saved path — merge must complete before this
- `setAuthAccessor()` in `main.ts` wires auth into API client — merge request will automatically include JWT
- Anonymous UUID in localStorage preserved after login — Story 3.4 reads it for merge

**From Story 2.4 (Cart UI):**
- Cart badge uses `useCartStore.totalItems` computed property — reactive, auto-updates
- Optimistic UI pattern: update store → API call → sync/rollback
- For merge: NOT optimistic — wait for server response since merge has complex logic

**From Epic 2 Retro:**
- Read-modify-write race condition on cart operations is a known deferred item
- For merge: acceptable risk since merge is a one-time operation per login session

## Dev Agent Record

### Agent Model Used
Claude Opus 4.6 (claude-opus-4-6)

### Debug Log References
- Vitest mock hoisting fix: `vi.mock()` factory cannot reference external `const` — inlined mock data inside factory
- Maven local repo: `security-lib` not installed — ran `mvn install -pl security-lib -DskipTests` before gateway tests

### Completion Notes List
- All 8 tasks completed, all tests passing
- Backend: 10 unit tests + 4 integration tests (all pass)
- Frontend: 7 new tests (5 cart store + 2 auth store), full regression 187/187 pass
- API Gateway: 27/27 tests pass with new security rule
- Integration tests added to existing `CartIntegrationTest.java` (not separate file) for consistency
- Cart merge is non-blocking in both `handleCallback()` and `initAuth()` — login never fails due to merge errors
- `mergeAnonymousCart()` swallows errors internally (console.error only), localStorage only cleared on success

### Change Log
- Created `CartMergeService.java` — merge logic reusing `Cart.addItem()` for dedup/quantity capping
- Created `MergeCartRequest.java` — `record` DTO with `@NotBlank anonymousCartId`
- Modified `CartRestController.java` — added `POST /merge` endpoint with `CartMergeService` injection
- Modified `GatewaySecurityConfig.java` — added `.authenticated()` for `POST /api/v1/cart/merge` before wildcard
- Created `CartMergeServiceTest.java` — 10 unit tests with Mockito
- Modified `CartIntegrationTest.java` — 4 integration tests for merge flow
- Modified `cartApi.ts` — added `mergeCart()` function
- Modified `useCartStore.ts` — added `mergeAnonymousCart()` action
- Modified `useAuthStore.ts` — hooked merge into `handleCallback()` and `initAuth()`
- Modified `useCartStore.spec.ts` — 5 merge tests
- Modified `useAuthStore.spec.ts` — 2 merge tests + `useCartStore` mock

### File List
**New files:**
- `backend/cart-service/src/main/java/com/robomart/cart/service/CartMergeService.java`
- `backend/cart-service/src/main/java/com/robomart/cart/dto/MergeCartRequest.java`
- `backend/cart-service/src/test/java/com/robomart/cart/unit/service/CartMergeServiceTest.java`

**Modified files:**
- `backend/cart-service/src/main/java/com/robomart/cart/controller/CartRestController.java`
- `backend/api-gateway/src/main/java/com/robomart/gateway/config/GatewaySecurityConfig.java`
- `backend/cart-service/src/test/java/com/robomart/cart/integration/CartIntegrationTest.java`
- `frontend/customer-website/src/api/cartApi.ts`
- `frontend/customer-website/src/stores/useCartStore.ts`
- `frontend/customer-website/src/stores/useAuthStore.ts`
- `frontend/customer-website/src/stores/__tests__/useCartStore.spec.ts`
- `frontend/customer-website/src/stores/__tests__/useAuthStore.spec.ts`
