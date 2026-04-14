# Story 8.2: Implement Graceful Degradation (3 Tiers)

Status: in-progress

## Story

As a customer,
I want the system to remain partially usable when some services are down,
so that I can still browse and manage my cart even during partial outages.

## Acceptance Criteria

1. **Given** Payment Service is down and circuit breaker is open
   **When** a customer attempts checkout
   **Then** order is held in `PAYMENT_PENDING` state, customer sees "Order received. Payment is being processed — we'll notify you when confirmed" (FR56)

2. **Given** Inventory Service is down
   **When** a customer attempts to place an order
   **Then** order placement is blocked with retry messaging: "We're experiencing a temporary issue. Please try again in a moment." (FR56)

3. **Given** Notification Service is down
   **When** order events are produced
   **Then** events queue in DLQ, orders proceed normally — notifications sent when service recovers (FR56)

4. **Given** Elasticsearch is down
   **When** a customer searches for products
   **Then** search falls back to PostgreSQL LIKE query with reduced functionality — results returned but without relevance ranking (FR56)

5. **Given** `DegradationBanner` component on Customer Website
   **When** Partial degradation is detected (503 from any service)
   **Then** yellow banner below header: "Some features are temporarily limited. You can browse and add to cart — checkout will be available shortly." Dismissible per session (UX-DR6)

6. **Given** complete API unavailability (network error — no response)
   **When** Maintenance tier is triggered
   **Then** full-page maintenance overlay: "We're performing maintenance and will be back shortly." Not dismissible (UX-DR6)

## Tasks / Subtasks

### Step 1: Add PAYMENT_PENDING to Order Status (Order Service)

- [x] **Task 1: Add `PAYMENT_PENDING` to `OrderStatus` enum** (AC: 1)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/enums/OrderStatus.java`
  - [ ] Add `PAYMENT_PENDING` after `PENDING`:
    ```java
    public enum OrderStatus {
        PENDING,
        PAYMENT_PENDING,       // Circuit open during payment — inventory reserved, awaiting payment retry
        INVENTORY_RESERVING,
        PAYMENT_PROCESSING,
        CONFIRMED,
        SHIPPED,
        DELIVERED,
        CANCELLED,
        PAYMENT_REFUNDING,
        INVENTORY_RELEASING
    }
    ```
  - [x] **No DB migration needed** — `status` column is `VARCHAR(50)`, not a PostgreSQL enum type (confirmed in `V1__init_order_schema.sql`)

### Step 2: Add shouldHoldAsPending to SagaStepException

- [x] **Task 2: Extend `SagaStepException`** (AC: 1)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/exception/SagaStepException.java`
  - [x] Add new field and constructor:
    ```java
    public class SagaStepException extends RuntimeException {
        private final boolean shouldCompensate;
        private final boolean shouldHoldAsPending;   // ← new

        // Existing constructor unchanged:
        public SagaStepException(String message, Throwable cause, boolean shouldCompensate) {
            super(message, cause);
            this.shouldCompensate = shouldCompensate;
            this.shouldHoldAsPending = false;
        }

        // New constructor for payment circuit-open case:
        public SagaStepException(String message, Throwable cause, boolean shouldCompensate, boolean shouldHoldAsPending) {
            super(message, cause);
            this.shouldCompensate = shouldCompensate;
            this.shouldHoldAsPending = shouldHoldAsPending;
        }

        public boolean isShouldCompensate() { return shouldCompensate; }
        public boolean isShouldHoldAsPending() { return shouldHoldAsPending; }
    }
    ```

### Step 3: Update ProcessPaymentStep for PAYMENT_PENDING

- [x] **Task 3: Modify `ProcessPaymentStep.execute()`** (AC: 1)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/steps/ProcessPaymentStep.java`
  - [x] Change the `PaymentServiceUnavailableException` catch block:
    ```java
    } catch (PaymentServiceUnavailableException e) {
        // Circuit open — do NOT cancel, do NOT compensate inventory
        // Hold order as PAYMENT_PENDING to allow retry when circuit closes
        log.warn("Payment circuit open for orderId={} — holding as PAYMENT_PENDING", order.getId());
        throw new SagaStepException(
            "Payment service circuit open for orderId=" + order.getId(), e,
            false,    // shouldCompensate: false — keep inventory reserved
            true      // shouldHoldAsPending: true — do NOT cancel
        );
    }
    ```
  - [x] **Important**: Do NOT change the `StatusRuntimeException` catch block — FAILED_PRECONDITION (payment declined) should still cancel + compensate normally

### Step 4: Update OrderSagaOrchestrator for PAYMENT_PENDING

- [x] **Task 4: Modify `OrderSagaOrchestrator.executeSaga()`** (AC: 1)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
  - [x] Update the `SagaStepException` catch block in the loop:
    ```java
    } catch (SagaStepException e) {
        logSagaStep(sagaId, sagaId, step.getName(), "FAILED", null, null, e.getMessage());

        if (e.isShouldHoldAsPending()) {
            // Payment circuit open — hold order in PAYMENT_PENDING, keep inventory reserved
            updateOrderStatus(order, OrderStatus.PAYMENT_PENDING);
            publishStatusChangedEvent(order, targetState);
            log.info("Order held as PAYMENT_PENDING for orderId={}", sagaId);
            return;  // success path — no cancellation, no compensation
        }

        if (e.isShouldCompensate()) {
            runCompensation(context, sagaId);
        }
        updateOrderStatus(order, OrderStatus.CANCELLED);
        publishStatusChangedEvent(order, targetState);
        log.warn("Saga failed at step={} for sagaId={}, cancellationReason={}", step.getName(), sagaId, order.getCancellationReason());
        return;
    }
    ```
  - [x] **`recoverStaleSagas()` — DO NOT add `PAYMENT_PENDING` to stale detection list.** `PAYMENT_PENDING` is a stable terminal state (not stale), orders remain there until a scheduled retry (out of scope for this story) or manual intervention.

### Step 5: Update ReserveInventoryStep for Inventory Circuit-Open Message

- [x] **Task 5: Set explicit cancellation reason in `ReserveInventoryStep` for circuit-open case** (AC: 2)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/saga/steps/ReserveInventoryStep.java`
  - [x] In the `InventoryServiceUnavailableException` catch block, set cancellation reason before throwing:
    ```java
    } catch (InventoryServiceUnavailableException e) {
        order.setCancellationReason("Inventory temporarily unavailable");   // ← set explicit reason
        throw new SagaStepException("Inventory service circuit open for orderId=" + order.getId(), e, true);
    }
    ```
  - [x] **Why**: `OrderRestController` distinguishes inventory failures vs payment failures by checking `cancellationReason`. Without an explicit reason here, the controller would fall through to the payment failure handler with a confusing message.

### Step 6: Update OrderRestController for PAYMENT_PENDING + Inventory Circuit Message

- [x] **Task 6: Update `OrderRestController.createOrder()`** (AC: 1, 2)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/web/OrderRestController.java`
  - [x] Add constant and update the status check logic:
    ```java
    private static final String INVENTORY_CANCELLATION_REASON = "Insufficient stock";
    private static final String INVENTORY_CIRCUIT_REASON = "Inventory temporarily unavailable";

    @PostMapping
    public ResponseEntity<ApiResponse<OrderSummaryResponse>> createOrder(...) {
        // ... existing code ...
        Order order = orderService.createOrder(userId, items, request.shippingAddress());

        if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
            // AC1: Payment circuit open — return 202 Accepted, customer informed to wait
            OrderSummaryResponse summary = new OrderSummaryResponse(
                    order.getId(), order.getCreatedAt(), order.getTotalAmount(),
                    order.getStatus(), order.getItems().size(), order.getCancellationReason());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(summary, MDC.get("traceId")));
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            String reason = order.getCancellationReason() != null ? order.getCancellationReason() : "Order processing failed";
            if (INVENTORY_CANCELLATION_REASON.equals(reason)) {
                throw new OrderInventoryFailedException(reason);
            }
            if (INVENTORY_CIRCUIT_REASON.equals(reason)) {
                // AC2: Inventory circuit open — retry messaging
                throw new OrderInventoryFailedException("We're experiencing a temporary issue. Please try again in a moment.");
            }
            throw new OrderPaymentFailedException(reason);
        }

        // CONFIRMED — 201 Created
        OrderSummaryResponse summary = new OrderSummaryResponse(...);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(summary, MDC.get("traceId")));
    }
    ```

### Step 7: Elasticsearch Fallback in Product Service

- [x] **Task 7: Add LIKE fallback query to `ProductRepository`** (AC: 4)
  - [x] File: `backend/product-service/src/main/java/com/robomart/product/repository/ProductRepository.java`
  - [x] Add method (existing file — append after `existsBySku`):
    ```java
    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.active = true " +
           "AND (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId)")
    Page<Product> searchByKeywordLike(
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            Pageable pageable);
    ```

- [x] **Task 8: Add Elasticsearch fallback to `ProductSearchService`** (AC: 4)
  - [x] File: `backend/product-service/src/main/java/com/robomart/product/service/ProductSearchService.java`
  - [x] Inject `ProductRepository` and `ProductMapper`:
    ```java
    private final ElasticsearchOperations elasticsearchOperations;
    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final Tracer tracer;

    public ProductSearchService(ElasticsearchOperations elasticsearchOperations,
                                ProductRepository productRepository,
                                ProductMapper productMapper,
                                Tracer tracer) { ... }
    ```
  - [x] Wrap Elasticsearch call with fallback in `search()`:
    ```java
    public PagedResponse<ProductListResponse> search(ProductSearchRequest request, Pageable pageable) {
        Pageable clampedPageable = clampPageSize(pageable);
        try {
            NativeQuery query = buildSearchQuery(request, clampedPageable);
            SearchHits<ProductDocument> searchHits = elasticsearchOperations.search(query, ProductDocument.class);
            // ... existing mapping logic ...
        } catch (Exception e) {
            log.warn("Elasticsearch unavailable, falling back to PostgreSQL LIKE search: {}", e.getMessage());
            return searchWithPostgresFallback(request, clampedPageable);
        }
    }

    private PagedResponse<ProductListResponse> searchWithPostgresFallback(ProductSearchRequest request, Pageable pageable) {
        String keyword = (request.keyword() != null && !request.keyword().isBlank()) ? request.keyword() : null;
        Page<Product> page = productRepository.searchByKeywordLike(keyword, request.categoryId(), pageable);
        List<ProductListResponse> products = page.getContent().stream()
                .map(productMapper::toProductListResponse)
                .toList();
        var pagination = new PaginationMeta(
                pageable.getPageNumber(), pageable.getPageSize(),
                page.getTotalElements(), page.getTotalPages());
        return new PagedResponse<>(products, pagination, getTraceId());
    }
    ```
  - [x] **Check `ProductMapper` for `toProductListResponse(Product)` method existence** — if it only has `toProductListResponse(ProductDocument)`, add a new overload that maps from `Product` entity to `ProductListResponse`.
  - [x] **Do NOT remove `@Cacheable` from `search()`** — the cache is keyed on request params; a fallback result will also be cached (with "no-trace" or a trace ID). This is acceptable behavior.

- [x] **Task 9: Update `ProductMapper` if needed** (AC: 4)
  - [x] File: `backend/product-service/src/main/java/com/robomart/product/mapper/ProductMapper.java`
  - [x] Check if `toProductListResponse(Product entity)` exists; if not, add:
    ```java
    // Fallback mapping from JPA entity (no relevance score — reduced functionality)
    public ProductListResponse toProductListResponse(Product product) {
        return new ProductListResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getRating(),
                product.getBrand(),
                product.getStockQuantity(),
                product.getCategory() != null ? product.getCategory().getId() : null,
                product.getCategory() != null ? product.getCategory().getName() : null,
                null  // images — not loaded in fallback (EntityGraph loads category, not images)
        );
    }
    ```

### Step 8: AC3 — Notification Service DLQ (No Code Change Required)

- [x] **Task 10: Verify notification DLQ behavior (documentation only)** (AC: 3)
  - AC3 is already implemented by Story 6.3's Dead Letter Queue. When Notification Service is down, Kafka delivery fails → after retries exhausted → message moves to DLQ (`*.dlq` topic) → order processing continues normally → when Notification Service recovers, DLQ retry mechanism delivers the event.
  - **No code change needed.** Document in Dev Notes.

### Step 9: Frontend — useUiStore

- [x] **Task 11: Create `useUiStore.ts`** (AC: 5, 6)
  - [x] File: `frontend/customer-website/src/stores/useUiStore.ts`
  - [x] Implementation:
    ```typescript
    import { ref } from 'vue'
    import { defineStore } from 'pinia'

    export type DegradationTier = 'normal' | 'partial' | 'maintenance'

    export const useUiStore = defineStore('ui', () => {
      const degradationTier = ref<DegradationTier>('normal')
      const isBannerDismissed = ref(
        sessionStorage.getItem('degradation-banner-dismissed') === 'true'
      )

      function setDegradationTier(tier: DegradationTier) {
        // Once maintenance is set, it cannot be downgraded by partial signals
        if (tier === 'maintenance' || degradationTier.value !== 'maintenance') {
          degradationTier.value = tier
        }
      }

      function dismissBanner() {
        isBannerDismissed.value = true
        sessionStorage.setItem('degradation-banner-dismissed', 'true')
      }

      function resetDegradation() {
        degradationTier.value = 'normal'
        isBannerDismissed.value = false
        sessionStorage.removeItem('degradation-banner-dismissed')
      }

      return {
        degradationTier,
        isBannerDismissed,
        setDegradationTier,
        dismissBanner,
        resetDegradation,
      }
    })
    ```

### Step 10: Frontend — Wire Axios interceptor to uiStore

- [x] **Task 12: Update `api/client.ts` to trigger degradation tier** (AC: 5, 6)
  - [x] File: `frontend/customer-website/src/api/client.ts`
  - [x] Add UI accessor pattern (same pattern as `setAuthAccessor`):
    ```typescript
    // UI store accessor — set by main.ts after store initialization
    let getUiState: (() => { setDegradationTier: (tier: 'normal' | 'partial' | 'maintenance') => void }) | null = null

    export function setUiAccessor(
      accessor: () => { setDegradationTier: (tier: 'normal' | 'partial' | 'maintenance') => void }
    ): void {
      getUiState = accessor
    }
    ```
  - [x] Update the response error interceptor to trigger degradation:
    ```typescript
    // In the existing error interceptor, after existing 401 handling:
    if (error.response) {
      const { status } = error.response
      if (status === 503) {
        getUiState?.().setDegradationTier('partial')
        return Promise.reject(new Error('Service temporarily unavailable'))
      }
      // ... rest of existing handling ...
    } else if (error.request) {
      // No response at all — network/gateway down
      getUiState?.().setDegradationTier('maintenance')
      return Promise.reject(new Error('Network error. Please check your connection.'))
    }
    ```
  - [x] **Keep existing network error message** for the fallback case. Maintenance tier is set when there is no response (complete network failure).

- [x] **Task 13: Register UI accessor in `main.ts`** (AC: 5, 6)
  - [x] File: `frontend/customer-website/src/main.ts`
  - [x] After auth accessor registration, add:
    ```typescript
    import { setUiAccessor } from '@/api/client'
    import { useUiStore } from '@/stores/useUiStore'
    
    // After app.use(pinia):
    const uiStore = useUiStore()
    setUiAccessor(() => uiStore)
    ```

### Step 11: Frontend — DegradationBanner Component

- [x] **Task 14: Create `DegradationBanner.vue`** (AC: 5, 6)
  - [x] File: `frontend/customer-website/src/components/common/DegradationBanner.vue`
  - [x] Implementation:
    ```vue
    <script setup lang="ts">
    import { computed } from 'vue'
    import { useUiStore } from '@/stores/useUiStore'

    const uiStore = useUiStore()

    const showPartialBanner = computed(
      () => uiStore.degradationTier === 'partial' && !uiStore.isBannerDismissed
    )
    const showMaintenanceOverlay = computed(() => uiStore.degradationTier === 'maintenance')
    </script>

    <template>
      <!-- Partial Degradation Banner (yellow, dismissible per session) -->
      <div
        v-if="showPartialBanner"
        class="degradation-banner degradation-banner--partial"
        role="alert"
        aria-live="assertive"
        aria-label="Service degradation notice"
      >
        <div class="degradation-banner__inner">
          <i class="pi pi-exclamation-triangle degradation-banner__icon" aria-hidden="true" />
          <span class="degradation-banner__message">
            Some features are temporarily limited. You can browse and add to cart — checkout will be available shortly.
          </span>
          <button
            class="degradation-banner__dismiss"
            type="button"
            aria-label="Dismiss service notice"
            @click="uiStore.dismissBanner()"
          >
            <i class="pi pi-times" aria-hidden="true" />
          </button>
        </div>
      </div>

      <!-- Maintenance Overlay (full-page, NOT dismissible) -->
      <div
        v-if="showMaintenanceOverlay"
        class="degradation-overlay"
        role="alertdialog"
        aria-live="assertive"
        aria-modal="true"
        aria-label="Maintenance notice"
      >
        <div class="degradation-overlay__card">
          <i class="pi pi-wrench degradation-overlay__icon" aria-hidden="true" />
          <h1 class="degradation-overlay__title">We'll be right back</h1>
          <p class="degradation-overlay__message">
            We're performing maintenance and will be back shortly.
          </p>
        </div>
      </div>
    </template>

    <style scoped>
    /* Partial Banner */
    .degradation-banner {
      width: 100%;
      z-index: 99;
    }
    .degradation-banner--partial {
      background-color: #fef9c3; /* yellow-100 */
      border-bottom: 1px solid #fde047; /* yellow-300 */
    }
    .degradation-banner__inner {
      display: flex;
      align-items: center;
      gap: 12px;
      max-width: 1280px;
      margin: 0 auto;
      padding: 10px 24px;
    }
    .degradation-banner__icon {
      color: #a16207; /* yellow-700 */
      font-size: 16px;
      flex-shrink: 0;
    }
    .degradation-banner__message {
      flex: 1;
      font-size: 14px;
      color: #713f12; /* yellow-900 */
    }
    .degradation-banner__dismiss {
      background: none;
      border: none;
      cursor: pointer;
      color: #a16207;
      padding: 4px;
      border-radius: 4px;
      display: flex;
      align-items: center;
    }
    .degradation-banner__dismiss:hover {
      background: #fde047;
    }

    /* Maintenance Overlay */
    .degradation-overlay {
      position: fixed;
      inset: 0;
      z-index: 9999;
      background: rgba(0, 0, 0, 0.85);
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .degradation-overlay__card {
      background: #ffffff;
      border-radius: 16px;
      padding: 48px;
      text-align: center;
      max-width: 480px;
      width: 90%;
    }
    .degradation-overlay__icon {
      font-size: 48px;
      color: var(--color-primary-600);
      margin-bottom: 24px;
    }
    .degradation-overlay__title {
      font-size: 24px;
      font-weight: 700;
      margin-bottom: 12px;
      color: var(--color-gray-900);
    }
    .degradation-overlay__message {
      font-size: 16px;
      color: var(--color-gray-600);
    }
    </style>
    ```

### Step 12: Frontend — Wire DegradationBanner into DefaultLayout

- [x] **Task 15: Update `DefaultLayout.vue` to include `DegradationBanner`** (AC: 5, 6)
  - [x] File: `frontend/customer-website/src/layouts/DefaultLayout.vue`
  - [x] Import and add banner after category nav (before `<main>`):
    ```vue
    <script setup lang="ts">
    // ... existing imports ...
    import DegradationBanner from '@/components/common/DegradationBanner.vue'
    </script>

    <template>
      <div class="layout">
        <header ...>...</header>
        <nav class="category-nav" ...>...</nav>
        <DegradationBanner />   <!-- ← add here, between nav and main -->
        <main id="main-content" class="main-content">
          <RouterView />
        </main>
        ...
      </div>
    </template>
    ```

### Step 13: Frontend — Handle PAYMENT_PENDING in Checkout

- [x] **Task 16: Update `useCheckoutStore.ts` to handle 202/PAYMENT_PENDING** (AC: 1)
  - [x] File: `frontend/customer-website/src/stores/useCheckoutStore.ts`
  - [x] Currently `placeOrder()` does `await router.push(`/order-confirmation/${result.data.id}`)` on success. The `result.data` is `OrderSummaryResponse` which includes `status`.
  - [x] The backend returns 202 Accepted for `PAYMENT_PENDING`. Axios resolves any 2xx as success. No changes needed to the happy path — `result.data.id` will be populated and the redirect works.
  - [x] On the `OrderConfirmationView` or the order detail view, show the friendly message when status is `PAYMENT_PENDING`.

- [x] **Task 17: Show PAYMENT_PENDING message in `OrderConfirmationView.vue`** (AC: 1)
  - [x] File: `frontend/customer-website/src/views/OrderConfirmationView.vue`
  - [x] Read the order and if `order.status === 'PAYMENT_PENDING'`, show: "Order received. Payment is being processed — we'll notify you when confirmed."
  - [x] This is a view-level display concern; the actual status label mapping should be added alongside existing status display.

### Step 14: Unit Tests

- [x] **Task 18: Unit tests for Order Service saga PAYMENT_PENDING path** (AC: 1)
  - [x] File: `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorTest.java`
  - [x] Test: when `ProcessPaymentStep.execute()` throws `SagaStepException(shouldHoldAsPending=true)`, `executeSaga()` transitions order to `PAYMENT_PENDING` (not `CANCELLED`)
  - [x] Test: compensation (releaseInventory) is NOT called when `shouldHoldAsPending=true`
  - [x] Test: outbox event `order_status_changed` is published with `newStatus=PAYMENT_PENDING`

- [x] **Task 19: Unit tests for Elasticsearch fallback in `ProductSearchServiceTest`** (AC: 4)
  - [x] File: `backend/product-service/src/test/java/com/robomart/product/unit/service/ProductSearchServiceTest.java` (create if not exists)
  - [x] Test: when `elasticsearchOperations.search()` throws `RuntimeException`, `searchWithPostgresFallback()` is called
  - [x] Test: fallback returns `PagedResponse` with products from `ProductRepository.searchByKeywordLike()`
  - [x] Mock `ElasticsearchOperations` (throw) + `ProductRepository` (return stubbed Page)

- [x] **Task 20: Unit tests for `DegradationBanner.vue`** (AC: 5, 6)
  - [x] File: `frontend/customer-website/src/components/common/__tests__/DegradationBanner.spec.ts`
  - [x] Test: renders nothing when tier is 'normal'
  - [x] Test: renders yellow banner when tier is 'partial' and banner not dismissed
  - [x] Test: banner not shown when tier is 'partial' but `isBannerDismissed` is true
  - [x] Test: dismiss button calls `uiStore.dismissBanner()`
  - [x] Test: renders full-page overlay when tier is 'maintenance'
  - [x] Test: maintenance overlay has `role="alertdialog"` and no dismiss button

- [x] **Task 21: Unit tests for `useUiStore`** (AC: 5, 6)
  - [x] File: `frontend/customer-website/src/stores/__tests__/useUiStore.spec.ts`
  - [x] Test: initial tier is 'normal'
  - [x] Test: `setDegradationTier('partial')` sets tier to 'partial'
  - [x] Test: once tier is 'maintenance', calling `setDegradationTier('partial')` does NOT downgrade
  - [x] Test: `dismissBanner()` sets `isBannerDismissed` to true and writes to sessionStorage
  - [x] Test: `resetDegradation()` resets state and clears sessionStorage

## Dev Notes

### AC3: Notification DLQ — Already Implemented (Story 6.3)

When Notification Service is down, Kafka consumer on the notification service fails delivery. After max retries (configured in notification service's `application.yml` under `spring.kafka.consumer.properties`), the message is forwarded to the DLQ topic (`order.events.dlq` or similar). Order Service's outbox poller and saga orchestrator are completely decoupled — they don't wait for notifications. When Notification Service recovers, the DLQ consumer retries and delivers the events. **No code change needed for AC3.**

### PAYMENT_PENDING State Machine Notes

`PAYMENT_PENDING` is a new stable state. What it means:
- Inventory **is reserved** (reservation ID set on order)
- Payment **has not been charged** (payment circuit was open)
- Order awaits either: (a) manual retry by ops, or (b) scheduled retry (Story 8.4 scope)
- Cancellation of a `PAYMENT_PENDING` order is out of scope for this story (not in AC)

`OrderService.cancelOrder()` currently allows `PENDING` and `CONFIRMED` statuses. `PAYMENT_PENDING` is NOT added to `CANCELLABLE_STATUSES` — keep as-is for now.

`recoverStaleSagas()` queries for `INVENTORY_RESERVING, PAYMENT_PROCESSING, PAYMENT_REFUNDING, INVENTORY_RELEASING`. Do NOT add `PAYMENT_PENDING` — it is intentionally stable, not stale.

### Elasticsearch Fallback — Reduced Functionality

The JPA fallback (`searchByKeywordLike`) provides:
- Case-insensitive keyword matching on product name (LOWER/LIKE)
- Category filtering (if `categoryId` provided)
- **Missing vs Elasticsearch**: brand filter, price range filter, rating filter, relevance ranking

This is acceptable per AC4: "results returned but without relevance ranking". Complex filters (brand, price range) fall back to no-filter behavior in the JPA query — only keyword + category are supported. If `brand`, `minPrice`, etc. are set but Elasticsearch is down, the JPA fallback ignores those filters. This is the degraded behavior.

### Frontend Degradation Tier Logic

Two signals trigger degradation:
1. **503 from any service** → `partial` tier (banner shown)
2. **Network error (no HTTP response)** → `maintenance` tier (overlay shown)

The `maintenance` tier cannot be downgraded to `partial` by subsequent 503s (only `normal` → `partial` → `maintenance` progression, not downgrade). Once maintenance overlay is shown, it stays until page refresh (rehydrates to normal).

Session storage key: `'degradation-banner-dismissed'` — survives navigation, cleared on browser close.

### No `PAYMENT_PENDING` in `OrderSummaryResponse` display

`OrderSummaryResponse` includes `OrderStatus status` which serializes as the enum name. The frontend `order.ts` types and display components need to handle `PAYMENT_PENDING` gracefully:
- In `OrdersView.vue` / `OrderConfirmationView.vue`: add `PAYMENT_PENDING` to the status label map
- Friendly label: "Payment Pending" with a yellow badge (existing components may use status string → label map)

### Spring AOP Not Involved in This Story

The Resilience4j AOP wrappers (`InventoryGrpcClient`, `PaymentGrpcClient`) from Story 8.1 are already in place. This story only changes the **exception handling strategy** in `ProcessPaymentStep` and `OrderSagaOrchestrator` — no new AOP configuration needed.

### Checkstyle Compliance

Pre-existing Checkstyle failures exist (TreeWalker config, not related to story code). Focus only on new/modified files. Use existing import ordering conventions from nearby files.

### Project Structure Notes

**Backend — Modified files:**
- `backend/order-service/src/main/java/com/robomart/order/enums/OrderStatus.java` — add `PAYMENT_PENDING`
- `backend/order-service/src/main/java/com/robomart/order/saga/exception/SagaStepException.java` — add overload constructor + `shouldHoldAsPending` field
- `backend/order-service/src/main/java/com/robomart/order/saga/steps/ProcessPaymentStep.java` — change catch block
- `backend/order-service/src/main/java/com/robomart/order/saga/steps/ReserveInventoryStep.java` — set cancellation reason
- `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java` — handle `shouldHoldAsPending`
- `backend/order-service/src/main/java/com/robomart/order/web/OrderRestController.java` — handle 202/PAYMENT_PENDING + circuit reason
- `backend/product-service/src/main/java/com/robomart/product/repository/ProductRepository.java` — add `searchByKeywordLike()`
- `backend/product-service/src/main/java/com/robomart/product/service/ProductSearchService.java` — add fallback + inject ProductRepository
- `backend/product-service/src/main/java/com/robomart/product/mapper/ProductMapper.java` — add `toProductListResponse(Product)` overload if missing

**Frontend — New files:**
- `frontend/customer-website/src/stores/useUiStore.ts`
- `frontend/customer-website/src/components/common/DegradationBanner.vue`
- `frontend/customer-website/src/components/common/__tests__/DegradationBanner.spec.ts`
- `frontend/customer-website/src/stores/__tests__/useUiStore.spec.ts`

**Frontend — Modified files:**
- `frontend/customer-website/src/api/client.ts` — add `setUiAccessor` + 503/network degradation triggers
- `frontend/customer-website/src/main.ts` — register UI accessor
- `frontend/customer-website/src/layouts/DefaultLayout.vue` — add `<DegradationBanner />`
- `frontend/customer-website/src/views/OrderConfirmationView.vue` — handle `PAYMENT_PENDING` status display

### References

- Epic 8 Story 8.2 requirements: `_bmad-output/planning-artifacts/epics.md` lines 1500–1531
- UX-DR6 (DegradationBanner spec): `_bmad-output/planning-artifacts/epics.md` line 199
- Architecture — uiStore: `_bmad-output/planning-artifacts/architecture.md` line 451
- Architecture — DegradationBanner.vue location: `_bmad-output/planning-artifacts/architecture.md` line 1520
- Architecture — Axios 503 → degradation: `_bmad-output/planning-artifacts/architecture.md` line 877
- Architecture — Resilience patterns: `_bmad-output/planning-artifacts/architecture.md` line 96
- Story 8.1 dev notes (circuit breaker patterns): `_bmad-output/implementation-artifacts/8-1-implement-circuit-breaker-resilience-patterns.md`
- Order status DB schema: `backend/order-service/src/main/resources/db/migration/V1__init_order_schema.sql`
- Existing saga orchestrator: `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
- Existing checkout store: `frontend/customer-website/src/stores/useCheckoutStore.ts`
- Existing API client pattern: `frontend/customer-website/src/api/client.ts` (`setAuthAccessor` pattern)
- Existing DefaultLayout: `frontend/customer-website/src/layouts/DefaultLayout.vue`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed pre-existing `Resilience4jConfig.java` compilation error: `CircuitBreakerConfigCustomizer`/`RetryConfigCustomizer` don't exist in resilience4j 2.3.0. Replaced with `@PostConstruct` approach using `CircuitBreakerRegistry.addConfiguration()` and `RetryRegistry.addConfiguration()`. `RetryConfig.Builder` has no `ignoreException(Predicate)` method — used `retryOnException(e -> !isBusinessError(e))` instead.
- `ProductMapperTest` failed with stale MapStruct generated class. Fixed by running `./mvnw clean install -pl :common-lib,:product-service -DskipTests`.
- `ProductSearchServiceCacheTest` needed `ProductRepository` and `ProductMapper` mock beans added to `TestConfig` after `ProductSearchService` constructor changed.
- `ProcessPaymentStepTest.shouldThrowWithCompensationWhenCircuitOpen` test was asserting old behavior (`shouldCompensate=true`). Updated to assert new behavior (`shouldCompensate=false, shouldHoldAsPending=true`).
- `ProductMapper` already has `toListResponse(Product)` method — used that name instead of `toProductListResponse(Product)`.

### Completion Notes List

- **AC1 (PAYMENT_PENDING)**: Added `PAYMENT_PENDING` to `OrderStatus` enum. Extended `SagaStepException` with `shouldHoldAsPending` flag. Updated `ProcessPaymentStep` to throw with `shouldHoldAsPending=true` and `shouldCompensate=false` when circuit is open. Updated `OrderSagaOrchestrator` to transition to `PAYMENT_PENDING` and skip compensation when flag is set. Updated `OrderRestController` to return 202 Accepted for `PAYMENT_PENDING` orders. Frontend `OrderConfirmationView` shows "Order received. Payment is being processed" for `PAYMENT_PENDING` status.
- **AC2 (Inventory circuit-open)**: Updated `ReserveInventoryStep` to set explicit `cancellationReason="Inventory temporarily unavailable"`. Updated `OrderRestController` to detect this reason and throw `OrderInventoryFailedException` with retry messaging.
- **AC3 (Notification DLQ)**: Already implemented by Story 6.3. No code changes needed.
- **AC4 (ES fallback)**: Added `searchByKeywordLike()` JPQL query to `ProductRepository`. Updated `ProductSearchService` to wrap Elasticsearch call in try/catch and fall back to PostgreSQL LIKE search via `searchWithPostgresFallback()`. Used existing `ProductMapper.toListResponse(Product)`.
- **AC5/AC6 (DegradationBanner)**: Created `useUiStore` with 3-tier degradation state management and sessionStorage persistence. Added `setUiAccessor` to `client.ts` and wired in `main.ts`. Created `DegradationBanner.vue` with yellow partial banner (dismissible) and full-page maintenance overlay (non-dismissible).
- **Tests**: 82 order service tests pass, 63 product service tests pass, 236 frontend tests pass.

### File List

**Backend — Modified files:**
- `backend/order-service/src/main/java/com/robomart/order/enums/OrderStatus.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/exception/SagaStepException.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/steps/ProcessPaymentStep.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/steps/ReserveInventoryStep.java`
- `backend/order-service/src/main/java/com/robomart/order/saga/OrderSagaOrchestrator.java`
- `backend/order-service/src/main/java/com/robomart/order/web/OrderRestController.java`
- `backend/order-service/src/main/java/com/robomart/order/config/Resilience4jConfig.java` (bug fix from Story 8.1)
- `backend/product-service/src/main/java/com/robomart/product/repository/ProductRepository.java`
- `backend/product-service/src/main/java/com/robomart/product/service/ProductSearchService.java`

**Backend — Modified test files:**
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/OrderSagaOrchestratorTest.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/saga/steps/ProcessPaymentStepTest.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/service/ProductSearchServiceTest.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/service/ProductSearchServiceCacheTest.java`

**Frontend — New files:**
- `frontend/customer-website/src/stores/useUiStore.ts`
- `frontend/customer-website/src/components/common/DegradationBanner.vue`
- `frontend/customer-website/src/components/common/__tests__/DegradationBanner.spec.ts`
- `frontend/customer-website/src/stores/__tests__/useUiStore.spec.ts`

**Frontend — Modified files:**
- `frontend/customer-website/src/api/client.ts`
- `frontend/customer-website/src/main.ts`
- `frontend/customer-website/src/layouts/DefaultLayout.vue`
- `frontend/customer-website/src/views/OrderConfirmationView.vue`
- `frontend/customer-website/src/types/order.ts`

### Review Findings

#### Decision-Needed (resolved)
- [x] [Review][Decision] D1 — AC4: Fallback silently drops `brand`, `minPrice`, `maxPrice`, `minRating` filters — **Resolved: (a) accept and document** — Added explicit comment in `searchWithPostgresFallback()` per AC4 "reduced functionality" allowance. Brand/price/rating filter support would be scope creep.
- [x] [Review][Decision] D2 — 503/network permanently sets degradation tier with no recovery path — **Resolved: (a) add success interceptor** — Added success interceptor calling `setDegradationTier('normal')` so partial tier auto-recovers; maintenance tier is protected by the existing guard and requires page reload.
- [x] [Review][Decision] D3 — `@Cacheable` caches degraded PostgreSQL fallback result — **Resolved: defer** — Properly preventing fallback caching requires either a `PagedResponse.fallback` marker + `@Cacheable(unless=...)` (needs common-lib change) or a CacheManager-based approach with manual key computation. Deferred to avoid scope creep; TTL bounds the window.

#### Patches (applied ✅ / action items ⚠️)
- ⚠️ [Review][Patch] P1 — `@PostConstruct addConfiguration` has no effect on already-created circuit breaker/retry instances — Resilience4j AOP instances are created eagerly during Spring wiring before `@PostConstruct` fires, so `ignoreException` predicate is silently never applied [`Resilience4jConfig.java:45-53`] — **left as action item** (requires investigating correct Resilience4j API for the version in use)
- ✅ [Review][Patch] P2 — FALSE POSITIVE — `publishStatusChangedEvent(order, targetState)` correctly uses `targetState=PAYMENT_PROCESSING` as `previousStatus` and `order.getStatus()=PAYMENT_PENDING` as `newStatus`; event payload is correct
- ✅ [Review][Patch] P3 — Added `IllegalArgumentException` guard in `SagaStepException` 4-arg constructor preventing `shouldCompensate=true AND shouldHoldAsPending=true` [`SagaStepException.java:20-25`]
- ✅ [Review][Patch] P4 — Moved `buildSearchQuery()` call outside try block so query-construction bugs are not swallowed by the ES fallback catch [`ProductSearchService.java:56-57`]
- ⚠️ [Review][Patch] P5 — JPQL `LIKE CONCAT('%', :keyword, '%')` does not escape `%` and `_` wildcard characters — LIKE-injection risk (DoS amplification) [`ProductRepository.java:33`] — **left as action item** (requires implementing a keyword escape utility)
- ✅ [Review][Patch] P6 — Extracted `CIRCUIT_OPEN_CANCELLATION_REASON` as `public static final` in `ReserveInventoryStep`; `OrderRestController` now references it directly — compile-time enforcement [`ReserveInventoryStep.java:26`, `OrderRestController.java:41`]
- ⚠️ [Review][Patch] P7 — `RetryConfig.retryOnException()` may overwrite an existing predicate in the default registry config [`Resilience4jConfig.java:50-53`] — **left as action item** (requires verifying default RetryConfig in application.yml)
- ⚠️ [Review][Patch] P8 — `setUiAccessor(() -> uiStore)` captures a store instance vs `setAuthAccessor` pattern [`main.ts:59-60`] — **left as action item** (low risk in SPA, pattern inconsistency only)
- ✅ [Review][Patch] P9 — Updated `shouldPublishStatusChangedEventWithPaymentPendingStatus` to capture `OutboxEvent` via `ArgumentCaptor` and assert `newStatus=PAYMENT_PENDING` and `previousStatus=PAYMENT_PROCESSING` in payload [`OrderSagaOrchestratorTest.java:300-313`]
- ✅ [Review][Patch] P10 — Added `assertThat(order.getCancellationReason()).isNull()` to `shouldThrowWithHoldAsPendingWhenCircuitOpen` test [`ProcessPaymentStepTest.java:112`]
- ⚠️ [Review][Patch] P11 — If `updateOrderStatus(PAYMENT_PENDING)` throws, order is stuck in `PAYMENT_PROCESSING` until stale saga recovery — `OrderService` emergency cancel only handles `PENDING` status [`OrderSagaOrchestrator.java:91`] — **left as action item**
- ✅ [Review][Patch] P12 — Added inner try-catch around `searchWithPostgresFallback()` call to log dual-outage (ES + PostgreSQL both down) and surface a clear error [`ProductSearchService.java:77-82`]
- ✅ [Review][Patch] P13 — Wrapped `getUiState?.()` calls in try-catch in Axios interceptor to prevent store errors from replacing the original API error [`client.ts:108,119`]
- ✅ [Review][Patch] P14 — FALSE POSITIVE — banner placement after category-nav is correct per story Task 15 ("add banner after category nav, before main"); AC5 "below header" refers to the visual region, not strict DOM order between header and secondary nav

#### Deferred
- [x] [Review][Defer] W1 — Inventory reservation leaks indefinitely for `PAYMENT_PENDING` orders — no retry/expiry/timeout mechanism [`OrderSagaOrchestrator.java`] — deferred, Story 8.4 scope (scheduled payment retry)
- [x] [Review][Defer] W2 — `PAYMENT_PENDING` absent from `recoverStaleSagas()` stale detection — stuck orders on restart are never scanned [`OrderSagaOrchestrator.java:264-266`] — deferred, spec explicitly marks PAYMENT_PENDING as stable (not stale); Story 8.4 adds retry
- [x] [Review][Defer] W3 — Customers attempting to cancel a `PAYMENT_PENDING` order receive a generic `OrderNotCancellableException` with no UX guidance [`OrderService.java:47`] — deferred, spec says PAYMENT_PENDING cancellation is out of scope for this story
- [x] [Review][Defer] W4 — `createProduct()` test helper uses reflection on `BaseEntity.id` — brittle to rename/module system changes [`ProductSearchServiceTest.java:555-570`] — deferred, pre-existing test smell, not a behavior bug
- [x] [Review][Defer] W5 — `isBannerDismissed` sessionStorage state persists across degradation events within a session — a dismissed partial banner will not reappear on subsequent 503s [`useUiStore.ts:8-10`] — deferred, by design (per-session dismissal is the spec intent)
- [x] [Review][Defer] W6 — Transient `PAYMENT_PROCESSING` entry written to `order_status_history` before `PAYMENT_PENDING` overrides it — status history shows a state the order never fully occupied [`OrderSagaOrchestrator.java:80,91`] — deferred, side effect of existing orchestrator design; fixing requires restructuring the step-target-state model

## Change Log

- 2026-04-14: Implemented Story 8.2 — Graceful Degradation 3 Tiers
  - AC1: Payment circuit-open → PAYMENT_PENDING order state (202 Accepted response)
  - AC2: Inventory circuit-open → CANCELLED with retry messaging
  - AC3: Notification DLQ already implemented (no code change)
  - AC4: Elasticsearch down → PostgreSQL LIKE fallback search
  - AC5/AC6: DegradationBanner component with partial/maintenance tiers, Axios interceptor wiring
  - Bug fix: Resilience4jConfig.java pre-existing compile error from Story 8.1
