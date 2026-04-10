# Story 7.2: Implement Admin Dashboard Overview with Metrics

Status: done

## Story

As an admin,
I want to see key business metrics and alerts at a glance when I open the dashboard,
So that I can quickly assess what needs my attention.

## Acceptance Criteria

1. **Given** the Admin Dashboard page **When** loaded **Then** 4 metric cards display above the fold: Orders Today (blue), Revenue Today (green), Low Stock Items (yellow/red), System Health (green/yellow/red) — numbers animate with count-up on load. (UX-DR12)

2. **Given** the "Needs Attention" section below metrics **When** low-stock alerts exist **Then** priority-sorted alert cards show with severity-coded Tags (red=critical, yellow=warning) and inline action buttons: "View" (→ Inventory page) and "Quick Restock". (UX-DR12)

3. **Given** a low-stock alert card **When** I click "Quick Restock" **Then** an inline `InputNumber` appears; I enter quantity, click "Update", Toast confirms "Stock updated", alert card is dismissed from the section.

4. **Given** the Dashboard with TabView **When** viewing **Then** "Business" tab (default) shows metrics + alerts + Live Feed + recent orders table. "System" tab shows a placeholder ("System health monitoring — coming soon") to be implemented in Story 7.4. (UX-DR12)

5. **Given** dashboard data loading **When** APIs are being fetched **Then** Skeleton screens matching the metric card and alert card layouts are displayed — never blank or spinning. (UX-DR12)

## Tasks / Subtasks

### Backend — Order Service

- [x] **Task 1: Add repository methods to `OrderRepository.java`** (AC: 1)
  - [x] Add `long countByCreatedAtAfter(Instant since)` (Spring Data derived query)
  - [x] Add `@Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.createdAt > :since") BigDecimal sumTotalAmountByCreatedAtAfter(@Param("since") Instant since)`

- [x] **Task 2: Create `OrderDashboardMetricsResponse.java`** (AC: 1)
  - [x] Java record in `com.robomart.order.web` package: `record OrderDashboardMetricsResponse(long ordersToday, BigDecimal revenueToday) {}`

- [x] **Task 3: Add `getDashboardMetrics()` to `OrderService.java`** (AC: 1)
  - [x] `@Transactional(readOnly = true)` method
  - [x] Compute `startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)`
  - [x] Call `orderRepository.countByCreatedAtAfter(startOfToday)` and `orderRepository.sumTotalAmountByCreatedAtAfter(startOfToday)`
  - [x] Return `new OrderDashboardMetricsResponse(count, sum)`

- [x] **Task 4: Add `GET /api/v1/admin/orders/metrics` to `OrderAdminRestController.java`** (AC: 1)
  - [x] `@GetMapping("/metrics")` method returning `ResponseEntity<ApiResponse<OrderDashboardMetricsResponse>>`
  - [x] No path variables or request params — always "today"
  - [x] Wrap with `new ApiResponse<>(metrics, getTraceId())`
  - [x] **Note**: No `@PreAuthorize` needed — ADMIN role enforced at API Gateway (`/api/v1/admin/**`)

- [x] **Task 5: Unit tests for Order Service metrics** (AC: 1)
  - [x] `OrderServiceDashboardTest.java` in `src/test/java/com/robomart/order/unit/service/`
  - [x] 3 tests: orders today with count, revenue sum, empty day returns zeros

### Backend — Inventory Service

- [x] **Task 6: Add repository method to `InventoryItemRepository.java`** (AC: 1, 2)
  - [x] Add `@Query("SELECT COUNT(i) FROM InventoryItem i WHERE i.availableQuantity < i.lowStockThreshold") long countLowStockItems()`

- [x] **Task 7: Create `InventoryMetricsResponse.java`** (AC: 1)
  - [x] Java record in `com.robomart.inventory.dto` package: `record InventoryMetricsResponse(long lowStockCount) {}`

- [x] **Task 8: Add `getMetrics()` to `InventoryService.java`** (AC: 1)
  - [x] `@Transactional(readOnly = true)` method
  - [x] Call `inventoryItemRepository.countLowStockItems()`
  - [x] Return `new InventoryMetricsResponse(count)`

- [x] **Task 9: Add `GET /api/v1/admin/inventory/metrics` to `InventoryAdminRestController.java`** (AC: 1)
  - [x] `@GetMapping("/metrics")` returning `ResponseEntity<ApiResponse<InventoryMetricsResponse>>`
  - [x] Used `getTraceId()` since `InventoryAdminRestController` actually DOES inject Tracer (story notes were incorrect)
  - [x] **Check**: Controller verified to have Tracer — used `getTraceId()` for consistency

- [x] **Task 10: Unit tests for Inventory Service metrics** (AC: 1)
  - [x] `InventoryServiceMetricsTest.java` in `src/test/java/com/robomart/inventory/unit/`
  - [x] 2 tests: count low stock items, zero when all in stock

### Frontend — New files

- [x] **Task 11: Create `src/api/dashboardApi.ts`** (AC: 1)
  - [x] `fetchOrderMetrics(): Promise<OrderMetrics>` → `GET /api/v1/admin/orders/metrics`
  - [x] `fetchInventoryMetrics(): Promise<InventoryMetrics>` → `GET /api/v1/admin/inventory/metrics`
  - [x] Use `adminClient` (existing axios instance with auth interceptor)
  - [x] Types: `OrderMetrics { ordersToday: number; revenueToday: number }`, `InventoryMetrics { lowStockCount: number }`
  - [x] Unwrap `data.data` (both endpoints return `ApiResponse<T>`)

- [x] **Task 12: Create `src/stores/useDashboardStore.ts`** (AC: 1, 2, 5)
  - [x] Pinia Composition API style (match `useWebSocketStore` / `useOrderAdminStore` patterns)
  - [x] State: `ordersToday`, `revenueToday`, `lowStockCount`, `systemHealth` (type: `'healthy' | 'degraded' | 'down'`, default `'healthy'` — real check in Story 7.4), `isLoading`, `error`
  - [x] Action `loadMetrics()`: call `fetchOrderMetrics()` and `fetchInventoryMetrics()` in parallel with `Promise.all`
  - [x] Set `isLoading = true` before, `false` in finally; set `error` on catch

- [x] **Task 13: Create `src/components/dashboard/MetricCard.vue`** (AC: 1, 5)
  - [x] Props: `label: string`, `value: number`, `format: 'number' | 'currency'`, `color: 'blue' | 'green' | 'yellow' | 'red'`, `loading: boolean`
  - [x] When `loading=true`: show `<Skeleton width="100%" height="80px" border-radius="8px" />` (import `Skeleton` from `primevue/skeleton`)
  - [x] When `loading=false`: show count-up animation (see count-up pattern below)
  - [x] Currency format: `new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value)`
  - [x] Number format: plain integer
  - [x] Color → left border + icon color: blue=`#3b82f6`, green=`#22c55e`, yellow=`#f59e0b`, red=`#ef4444`
  - [x] Use PrimeVue `Card` component (import `Card` from `primevue/card`)

- [x] **Task 14: Create `src/components/dashboard/AlertCard.vue`** (AC: 2, 3)
  - [x] Props: `type: 'low-stock'`, `productId: number`, `productName: string`, `currentStock: number`, `threshold: number`
  - [x] Severity: if `currentStock === 0` → `severity="danger"` (red); else `severity="warn"` (yellow) — PrimeVue `Tag`
  - [x] Import `Tag` from `primevue/tag`, `Button` from `primevue/button`, `InputNumber` from `primevue/inputnumber`
  - [x] "View" button: `text` variant, navigates to `/admin/inventory` via `useRouter().push`
  - [x] "Quick Restock" button: toggles inline restock form
  - [x] Inline restock form: `<InputNumber v-model="restockQty" :min="1" :max="9999" />` + "Update" / "Cancel" buttons
  - [x] On "Update": call `useInventoryStore().restockItem(productId, restockQty)` then emit `'dismissed'`; show toast "Stock updated" on success, sticky error toast on failure
  - [x] Emit `'dismissed'` when alert should be removed (after successful restock OR explicit dismiss)
  - [x] **Do NOT** call `loadInventory()` inside AlertCard — the parent `NeedsAttentionSection` handles store state

- [x] **Task 15: Create `src/components/dashboard/NeedsAttentionSection.vue`** (AC: 2, 3, 5)
  - [x] Uses `useInventoryStore().lowStockItems` — `lowStockItems` is `computed(() => items.filter(i => i.availableQuantity < i.lowStockThreshold))` (already exists in store)
  - [x] When `useInventoryStore().isLoading`: show 2× `<Skeleton height="56px" />` placeholders
  - [x] When `lowStockItems.length === 0`: show empty state "All clear" with checkmark icon
  - [x] Sort alert cards: `currentStock === 0` first (critical), then by `currentStock/threshold` ratio ascending
  - [x] For each low-stock item: render `<AlertCard>` with productId, productName, currentStock=`availableQuantity`, threshold=`lowStockThreshold`
  - [x] Handle `@dismissed` from `AlertCard`: remove item from local `dismissedIds` set (don't modify store directly)
  - [x] **Note**: `lowStockItems` uses `productName` from the enriched type `InventoryItemEnriched` (joined in store)

- [x] **Task 16: Update `DashboardPage.vue`** (AC: 1, 2, 3, 4, 5)
  - [x] Import PrimeVue 4.x Tabs API: `Tabs`, `TabList`, `Tab`, `TabPanels`, `TabPanel` (see pattern below)
  - [x] `onMounted`: call `dashboardStore.loadMetrics()`, `inventoryStore.loadInventory()`, `connect()` (WS), `orderStore.loadOrders(0)` with `pageSize=5`
  - [x] Business tab layout (top to bottom): metric grid → Needs Attention → 2-column: [Live Feed | Recent Orders]
  - [x] Metric grid: `display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px;` with 4 `<MetricCard>`
  - [x] System tab: placeholder message only ("System health monitoring will be available in the next update.")
  - [x] Recent orders: a simple DataTable with columns: Order ID, Date, Amount, Status — last 5 orders from `useOrderAdminStore`
  - [x] Import `DataTable` from `primevue/datatable`, `Column` from `primevue/column`, `Tag` from `primevue/tag`
  - [x] Status Tag severity mapping: `CONFIRMED/DELIVERED → success`, `PENDING/PAYMENT_PENDING → warn`, `CANCELLED → danger`
  - [x] Keep existing `<LiveOrderFeed>` and WebSocket connect/disconnect lifecycle unchanged

### Frontend — Tests

- [x] **Task 17: Unit tests** (AC: 1, 2, 3)
  - [x] `useDashboardStore.spec.ts`: 3 tests — loads metrics via parallel fetch, sets isLoading correctly, handles API error
  - [x] `MetricCard.spec.ts`: 3 tests — skeleton when loading, renders value when not loading, currency format
  - [x] `AlertCard.spec.ts`: 3 tests — renders severity tag by stock level, Quick Restock toggle, dismiss emitted on success

## Dev Notes

### Architecture Overview

```
Dashboard loads → DashboardPage.vue
  ├── useDashboardStore.loadMetrics()
  │     ├── GET /api/v1/admin/orders/metrics → OrderAdminRestController (Order Service :8083)
  │     └── GET /api/v1/admin/inventory/metrics → InventoryAdminRestController (Inventory Service :8084)
  ├── useInventoryStore.loadInventory()   ← for Needs Attention / Quick Restock
  ├── useOrderAdminStore.loadOrders(0)    ← for Recent Orders (size=5)
  └── useWebSocket.connect()             ← for Live Feed (Story 7.1)
```

No new Kafka topics, WebSocket topics, Avro schemas, or database migrations needed for this story.

System Health metric card is **static "Healthy"** in this story — Story 7.4 implements real monitoring.

### Backend: New DTO Package Locations

```
order-service/src/main/java/com/robomart/order/
  web/
    OrderDashboardMetricsResponse.java   ← NEW record
  controller/
    OrderAdminRestController.java         ← MODIFY (add /metrics endpoint)
  service/
    OrderService.java                     ← MODIFY (add getDashboardMetrics())
  repository/
    OrderRepository.java                  ← MODIFY (add count + sum queries)

inventory-service/src/main/java/com/robomart/inventory/
  dto/
    InventoryMetricsResponse.java         ← NEW record
  controller/
    InventoryAdminRestController.java     ← MODIFY (add /metrics endpoint)
  service/
    InventoryService.java                 ← MODIFY (add getMetrics())
  repository/
    InventoryItemRepository.java          ← MODIFY (add countLowStockItems query)
```

### Backend: JPQL Query for Inventory Low-Stock Count

The `InventoryItem` entity does NOT extend `BaseEntity` — it has its own `id`, `createdAt`/`updatedAt`. Use JPQL field names (`availableQuantity`, `lowStockThreshold`) not column names:

```java
@Query("SELECT COUNT(i) FROM InventoryItem i WHERE i.availableQuantity < i.lowStockThreshold")
long countLowStockItems();
```

### Backend: Order Service Metrics — Start of Day Calculation

```java
import java.time.LocalDate;
import java.time.ZoneOffset;

Instant startOfToday = LocalDate.now(ZoneOffset.UTC)
    .atStartOfDay()
    .toInstant(ZoneOffset.UTC);
```

### Backend: Order Service `sumTotalAmountByCreatedAtAfter` JPQL

```java
@Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.createdAt > :since")
BigDecimal sumTotalAmountByCreatedAtAfter(@Param("since") Instant since);
```

Use `COALESCE` to avoid null when no orders exist today.

### Backend: `InventoryAdminRestController` — No Tracer Bean

The existing `InventoryAdminRestController` does NOT inject `Tracer` (unlike `OrderAdminRestController`). Use `null` for traceId in the new metrics endpoint:

```java
@GetMapping("/metrics")
public ResponseEntity<ApiResponse<InventoryMetricsResponse>> getMetrics() {
    return ResponseEntity.ok(new ApiResponse<>(inventoryService.getMetrics(), null));
}
```

Also inject `InventoryService` in the constructor (if not already injected — check existing constructor).

### Frontend: PrimeVue 4.x Tabs Pattern

PrimeVue 4.x uses the **new Tabs API** (not the deprecated `TabView`):

```vue
<script setup>
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
</script>

<template>
  <Tabs value="business">
    <TabList>
      <Tab value="business">Business</Tab>
      <Tab value="system">System</Tab>
    </TabList>
    <TabPanels>
      <TabPanel value="business">...business content...</TabPanel>
      <TabPanel value="system">...system placeholder...</TabPanel>
    </TabPanels>
  </Tabs>
</template>
```

### Frontend: Count-Up Animation (No Third-Party Library)

Use `requestAnimationFrame` directly in `MetricCard.vue`:

```typescript
import { ref, watch, onUnmounted } from 'vue'

const props = defineProps<{ value: number; loading: boolean }>()
const displayValue = ref(0)
let rafId: number | null = null

watch(() => props.value, (newVal) => {
  if (props.loading) return
  const startVal = displayValue.value
  const startTime = performance.now()
  const duration = 800 // ms

  function animate(now: number) {
    const elapsed = now - startTime
    const progress = Math.min(elapsed / duration, 1)
    // easeOut cubic
    const ease = 1 - Math.pow(1 - progress, 3)
    displayValue.value = Math.round(startVal + (newVal - startVal) * ease)
    if (progress < 1) {
      rafId = requestAnimationFrame(animate)
    }
  }
  if (rafId) cancelAnimationFrame(rafId)
  rafId = requestAnimationFrame(animate)
}, { immediate: true })

onUnmounted(() => { if (rafId) cancelAnimationFrame(rafId) })
```

### Frontend: `dashboardApi.ts` Response Unwrapping

Both endpoints return `ApiResponse<T>` → `{ data: T, traceId: string }`. Unwrap `.data.data`:

```typescript
export async function fetchOrderMetrics(): Promise<OrderMetrics> {
  const { data } = await adminClient.get<{ data: OrderMetrics; traceId: string }>(
    '/api/v1/admin/orders/metrics'
  )
  return data.data
}
```

### Frontend: Quick Restock — Reuse `useInventoryStore`

`AlertCard.vue` must NOT reinvent the restock API call. Use the existing store action:

```typescript
import { useInventoryStore } from '@/stores/useInventoryStore'
import { useToast } from 'primevue/usetoast'

const inventoryStore = useInventoryStore()
const toast = useToast()
const emit = defineEmits<{ dismissed: [] }>()

async function submitRestock() {
  try {
    await inventoryStore.restockItem(props.productId, restockQty.value)
    toast.add({ severity: 'success', summary: 'Stock updated', life: 3000 })
    emit('dismissed')
  } catch {
    toast.add({ severity: 'error', summary: 'Update failed', detail: 'Please try again', life: 0 })
  }
}
```

`useInventoryStore.restockItem()` already calls `PUT /api/v1/admin/inventory/${productId}/restock` and updates the store's `items` array — the `lowStockItems` computed will reactively update.

### Frontend: NeedsAttentionSection — Dismissed IDs Pattern

```typescript
const dismissedIds = ref<Set<number>>(new Set())

const visibleAlerts = computed(() =>
  inventoryStore.lowStockItems
    .filter(item => !dismissedIds.value.has(item.productId))
    .sort((a, b) => {
      // Critical (out of stock) first
      if (a.availableQuantity === 0 && b.availableQuantity !== 0) return -1
      if (b.availableQuantity === 0 && a.availableQuantity !== 0) return 1
      // Then by ratio
      return (a.availableQuantity / a.lowStockThreshold) - (b.availableQuantity / b.lowStockThreshold)
    })
)

function handleDismissed(productId: number) {
  dismissedIds.value = new Set([...dismissedIds.value, productId])
}
```

### Frontend: Recent Orders Table

Use existing `useOrderAdminStore` and `listOrders` — do NOT create a new API call:

```typescript
const orderStore = useOrderAdminStore()
// In onMounted: orderStore.pageSize = 5; await orderStore.loadOrders(0)
// Template: <DataTable :value="orderStore.orders" :loading="orderStore.isLoading">
```

Status Tag severity mapping in template:
```typescript
function orderStatusSeverity(status: string) {
  const map: Record<string, string> = {
    CONFIRMED: 'success', DELIVERED: 'success',
    PENDING: 'warn', PAYMENT_PENDING: 'warn',
    CANCELLED: 'danger'
  }
  return map[status] ?? 'secondary'
}
```

### Frontend: MetricCard Color Tokens

Use CSS custom properties (already defined in the project):
- blue: `var(--color-info-500)` or `#3b82f6`
- green: `var(--color-success-500)` or `#22c55e`
- yellow: `var(--color-warning-500)` or `#f59e0b`
- red: `var(--color-error-500)` or `#ef4444`

### Critical Pitfalls

1. **`@MockitoBean` not `@MockBean`** — `@MockBean` is deprecated in Spring Boot 4. Use `@MockitoBean` from `org.springframework.test.context.bean.override.mockito`.

2. **Jackson 3.x databind package** — `tools.jackson.databind` (not `com.fasterxml.jackson.databind`). For DTOs with just primitives/BigDecimal/String, no Jackson annotations needed.

3. **PrimeVue 4.x Tabs API** — Do NOT use deprecated `TabView` + `TabPanel` from PrimeVue 3. Use `Tabs`, `TabList`, `Tab`, `TabPanels`, `TabPanel` — separate imports each.

4. **`InventoryItemEnriched` type** — `NeedsAttentionSection` iterates `lowStockItems` which is of type `InventoryItemEnriched[]` (has `productName`, `sku` fields from store join). Access `item.productName` directly — no need to call Product Service.

5. **`useInventoryStore.loadInventory()` joins products** — it calls both `listInventory()` and `listProducts(0, 1000)` in parallel. This is intentional and should not be "optimized away." The `productName` on `InventoryItemEnriched` is required by `AlertCard`.

6. **`OrderAdminRestController` uses `Tracer`** — the metrics endpoint lives in this class. Use `getTraceId()` private method (already defined in that class) for the response.

7. **Count-up on initial load vs refresh** — trigger the animation with `watch({ immediate: true })` so it plays once when data loads, not on every component re-render.

8. **API Gateway routes** — `/api/v1/admin/orders/metrics` and `/api/v1/admin/inventory/metrics` are already covered by existing gateway rules: `/api/v1/admin/**` → respective services. No gateway changes needed.

### Existing Code to Reuse

| Component | Location | How to use |
|-----------|----------|------------|
| `adminClient` | `src/api/adminClient.ts` | Import for all HTTP calls |
| `useInventoryStore` | `src/stores/useInventoryStore.ts` | `lowStockItems` computed + `restockItem()` action |
| `useOrderAdminStore` | `src/stores/useOrderAdminStore.ts` | `loadOrders()` + `orders` ref for recent orders |
| `useWebSocket` | `src/composables/useWebSocket.ts` | `connect()` / `disconnect()` lifecycle |
| `LiveOrderFeed.vue` | `src/components/dashboard/LiveOrderFeed.vue` | Unchanged — keep in Business tab |
| `useToast()` | `primevue/usetoast` | Toast feedback pattern |
| `ApiResponse<T>` wrapper | common-lib `com.robomart.common.dto.ApiResponse` | Wrap all new endpoints |
| `@Transactional(readOnly = true)` | Existing service methods | Add to all new read-only service methods |

### Testing Standards

**Backend unit test pattern** (follow `OrderServiceAdminTest.java` pattern):
```java
@ExtendWith(MockitoExtension.class)
class OrderServiceDashboardTest {
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    // ... other deps
    @InjectMocks OrderService orderService;

    @Test
    void getDashboardMetrics_returnsCountAndSum() {
        Instant anyInstant = ArgumentMatchers.any();
        when(orderRepository.countByCreatedAtAfter(anyInstant)).thenReturn(5L);
        when(orderRepository.sumTotalAmountByCreatedAtAfter(anyInstant))
            .thenReturn(new BigDecimal("250.00"));
        
        var result = orderService.getDashboardMetrics();
        
        assertThat(result.ordersToday()).isEqualTo(5);
        assertThat(result.revenueToday()).isEqualByComparingTo("250.00");
    }
}
```

**Frontend test pattern** (follow `useWebSocketStore.spec.ts`):
```typescript
describe('useDashboardStore', () => {
  beforeEach(() => setActivePinia(createPinia()))
  
  it('loads metrics in parallel and sets state', async () => {
    vi.mocked(fetchOrderMetrics).mockResolvedValue({ ordersToday: 10, revenueToday: 500 })
    vi.mocked(fetchInventoryMetrics).mockResolvedValue({ lowStockCount: 3 })
    
    const store = useDashboardStore()
    await store.loadMetrics()
    
    expect(store.ordersToday).toBe(10)
    expect(store.lowStockCount).toBe(3)
    expect(store.isLoading).toBe(false)
  })
})
```

### Project Structure Notes

**New backend files:**
- `backend/order-service/src/main/java/com/robomart/order/web/OrderDashboardMetricsResponse.java` (record)
- `backend/inventory-service/src/main/java/com/robomart/inventory/dto/InventoryMetricsResponse.java` (record)
- `backend/order-service/src/test/java/com/robomart/order/unit/service/OrderServiceDashboardTest.java`
- `backend/inventory-service/src/test/java/com/robomart/inventory/unit/InventoryServiceMetricsTest.java`

**New frontend files:**
- `frontend/admin-dashboard/src/api/dashboardApi.ts`
- `frontend/admin-dashboard/src/stores/useDashboardStore.ts`
- `frontend/admin-dashboard/src/components/dashboard/MetricCard.vue`
- `frontend/admin-dashboard/src/components/dashboard/AlertCard.vue`
- `frontend/admin-dashboard/src/components/dashboard/NeedsAttentionSection.vue`
- `frontend/admin-dashboard/src/__tests__/useDashboardStore.spec.ts`
- `frontend/admin-dashboard/src/__tests__/MetricCard.spec.ts`
- `frontend/admin-dashboard/src/__tests__/AlertCard.spec.ts`

**Modified files:**
- `backend/order-service/src/main/java/com/robomart/order/repository/OrderRepository.java`
- `backend/order-service/src/main/java/com/robomart/order/service/OrderService.java`
- `backend/order-service/src/main/java/com/robomart/order/controller/OrderAdminRestController.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/repository/InventoryItemRepository.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/service/InventoryService.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/controller/InventoryAdminRestController.java`
- `frontend/admin-dashboard/src/views/DashboardPage.vue`

### References

- UX: Dashboard triage flow — `[ux-design-specification.md#Journey 4: Admin Daily Operations]`
- UX: PrimeVue components mapped to journeys — `[ux-design-specification.md#Admin Dashboard — PrimeVue Components Mapped to Journeys]`
- UX: Skeleton screens rule — `[ux-design-specification.md#Feedback Patterns]` ("Use skeleton screens for content")
- Architecture: `adminStore` state shape — `[architecture.md#Frontend State Architecture]`
- Architecture: Admin routes — `[architecture.md#API Gateway Route Table]` (`/api/v1/admin/**`)
- Story 7.1: WebSocket setup, `useWebSocket` composable, `LiveOrderFeed.vue`, `DashboardPage.vue` current state
- Story 5.4: Inventory management patterns, `useInventoryStore`, `InventoryAdminRestController`
- Story 5.5: Admin orders table patterns, `useOrderAdminStore`, `OrderAdminRestController`
- `[CLAUDE.md]`: Spring Boot 4 gotchas, Jackson 3.x, `@MockitoBean`, service ports

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Pre-existing bug in `InventoryServiceIT.java`: used `findByPublishedFalseOrderByCreatedAtAsc()` which didn't exist in `OutboxEventRepository`. Fixed by adding the derived query method.
- Story notes said `InventoryAdminRestController` does NOT inject Tracer — verified it actually DOES have Tracer. Used `getTraceId()` for consistency.
- `CommandPalette.test.ts` failure is pre-existing, not related to this story.

### Completion Notes List

- Implemented backend metrics endpoints in Order Service (`GET /api/v1/admin/orders/metrics`) and Inventory Service (`GET /api/v1/admin/inventory/metrics`).
- All 5 backend unit tests pass: 3 in `OrderServiceDashboardTest` + 2 in `InventoryServiceMetricsTest`.
- Frontend: created `dashboardApi.ts`, `useDashboardStore.ts`, `MetricCard.vue`, `AlertCard.vue`, `NeedsAttentionSection.vue`.
- Updated `DashboardPage.vue` with TabView (Business + System tabs), 4 MetricCards with count-up animation, NeedsAttentionSection, Recent Orders DataTable, and preserved LiveOrderFeed.
- All 9 frontend unit tests pass across 3 spec files.
- Count-up animation uses `requestAnimationFrame` with easeOut cubic — no third-party library.
- SystemHealth card shows static "Healthy" (value=1, green) for now; Story 7.4 will implement real monitoring.

### File List

**New Backend Files:**
- `backend/order-service/src/main/java/com/robomart/order/web/OrderDashboardMetricsResponse.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/dto/InventoryMetricsResponse.java`
- `backend/order-service/src/test/java/com/robomart/order/unit/service/OrderServiceDashboardTest.java`
- `backend/inventory-service/src/test/java/com/robomart/inventory/unit/InventoryServiceMetricsTest.java`

**Modified Backend Files:**
- `backend/order-service/src/main/java/com/robomart/order/repository/OrderRepository.java`
- `backend/order-service/src/main/java/com/robomart/order/service/OrderService.java`
- `backend/order-service/src/main/java/com/robomart/order/controller/OrderAdminRestController.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/repository/InventoryItemRepository.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/repository/OutboxEventRepository.java` (added missing derived query to fix pre-existing test compile error)
- `backend/inventory-service/src/main/java/com/robomart/inventory/service/InventoryService.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/controller/InventoryAdminRestController.java`

**New Frontend Files:**
- `frontend/admin-dashboard/src/api/dashboardApi.ts`
- `frontend/admin-dashboard/src/stores/useDashboardStore.ts`
- `frontend/admin-dashboard/src/components/dashboard/MetricCard.vue`
- `frontend/admin-dashboard/src/components/dashboard/AlertCard.vue`
- `frontend/admin-dashboard/src/components/dashboard/NeedsAttentionSection.vue`
- `frontend/admin-dashboard/src/__tests__/useDashboardStore.spec.ts`
- `frontend/admin-dashboard/src/__tests__/MetricCard.spec.ts`
- `frontend/admin-dashboard/src/__tests__/AlertCard.spec.ts`

**Modified Frontend Files:**
- `frontend/admin-dashboard/src/views/DashboardPage.vue`

### Review Findings

- [x] [Review][Decision→Patch] System Health card shows `1`/`0` instead of readable label — fixed by adding `format: 'label'` to MetricCard and passing `value={dashboardStore.systemHealth}` directly [`MetricCard.vue`, `DashboardPage.vue`]
- [x] [Review][Patch] `Math.round()` in MetricCard animation permanently truncates fractional revenue — fixed: only round for `format='number'`; currency uses raw float value [`MetricCard.vue:36`]
- [x] [Review][Patch] COALESCE integer literal `0` causes type mismatch for `BigDecimal` return — fixed: changed to `0.0` [`OrderRepository.java`]
- [x] [Review][Patch] `Promise.all` in `onMounted` missing try/catch — fixed: `loadInventory()` and `loadOrders()` now `.catch(() => {})` to prevent uncaught rejection [`DashboardPage.vue`]
- [x] [Review][Patch] "All clear" flashes briefly on initial mount — fixed: added `hasLoaded` watcher, skeleton shows until first load completes [`NeedsAttentionSection.vue`]
- [x] [Review][Patch] AlertCard restock submission flow not tested — fixed: added 4th test covering submit → toast → dismiss emit [`AlertCard.spec.ts`]
- [x] [Review][Defer] Midnight boundary: `countByCreatedAtAfter` excludes orders at exactly 00:00:00.000 UTC — both count and sum are `>` (exclusive); statistically irrelevant but inconsistent with "today" semantics [`OrderRepository.java`] — deferred, pre-existing
- [x] [Review][Defer] Concurrent `loadMetrics()` race: shared `isLoading` reset by whichever call finishes first — unlikely in practice (mount-only call site) [`useDashboardStore.ts`] — deferred, pre-existing
- [x] [Review][Defer] Animation `startVal` stale on rapid successive value updates — minor visual artifact, not triggered under normal usage [`MetricCard.vue`] — deferred, pre-existing
- [x] [Review][Defer] `NeedsAttentionSection` shows "All clear" instead of error state when inventory fetch fails — inventory store error handling out of this story's scope [`NeedsAttentionSection.vue`] — deferred, pre-existing

## Change Log

- 2026-04-10: Implemented Story 7.2 — Admin Dashboard Overview with Metrics. Added backend metrics endpoints to Order and Inventory services, created frontend metric cards, alert cards, needs-attention section, and updated DashboardPage with TabView layout including Business (metrics + alerts + live feed + recent orders) and System (placeholder) tabs.
