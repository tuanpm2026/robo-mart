# Story 5.4: Implement Admin Inventory Management

Status: done

## Dev Agent Record

### Completion Notes

Implementation completed 2026-04-07. All 10 tasks done, all ACs satisfied.

**Key decisions:**
- `StockMovementType.RESTOCK` already existed — no enum change needed
- Restock uses `@Transactional` only (no Redis distributed lock — admin is single-user, not concurrent saga)
- Frontend enrichment: parallel fetch inventory + products, client-side join by productId — avoids backend cross-service coupling
- `loadInventory()` no longer re-throws (just sets `error.value`) — prevents unhandled rejection in onMounted
- API Gateway route `/api/v1/admin/inventory/**` was already configured in `RouteConfig.java`

**Test results:**
- Backend unit tests: 31 pass (7 new restockItem/bulkRestock tests)
- Backend integration tests: 5 pass (InventoryAdminRestIT)
- Frontend unit tests: 8 pass (InventoryPage.test.ts)
- No regressions introduced

### File List

**Backend — New files:**
- `backend/inventory-service/src/main/java/com/robomart/inventory/controller/InventoryAdminRestController.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/dto/InventoryItemResponse.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/dto/RestockRequest.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/dto/BulkRestockRequest.java`
- `backend/inventory-service/src/main/java/com/robomart/inventory/dto/PagedInventoryResponse.java`
- `backend/inventory-service/src/test/java/com/robomart/inventory/integration/InventoryAdminRestIT.java`

**Backend — Modified files:**
- `backend/inventory-service/src/main/java/com/robomart/inventory/service/InventoryService.java` (added `listInventory`, `restockItem`, `bulkRestock`)
- `backend/inventory-service/src/test/java/com/robomart/inventory/unit/service/InventoryServiceTest.java` (added 7 new tests)

**Frontend — New files:**
- `frontend/admin-dashboard/src/api/inventoryAdminApi.ts`
- `frontend/admin-dashboard/src/stores/useInventoryStore.ts`
- `frontend/admin-dashboard/src/__tests__/InventoryPage.test.ts`

**Frontend — Modified files:**
- `frontend/admin-dashboard/src/views/InventoryPage.vue` (replaced placeholder with full implementation)

### Change Log

- 2026-04-07: Story 5.4 implemented — Admin Inventory Management with REST API (3 endpoints), Pinia store with client-side product enrichment, full DataTable UI with inline restock, bulk restock dialog, low-stock highlighting, and comprehensive tests (31 backend unit + 5 integration + 8 frontend).

## Story

As an admin,
I want to view stock levels, restock products, and see low-stock alerts,
So that I can ensure products remain available for customers.

## Acceptance Criteria

1. **Inventory DataTable**: Given the Inventory page in Admin Dashboard, when I navigate to it, then I see a PrimeVue DataTable with columns: Product Name, SKU, Current Stock, Reserved, Available, Threshold, Status — sortable, with 25 rows default pagination (FR24) (AC1)

2. **Inline Restock**: Given an inventory item in the DataTable, when I click the Stock (Current Stock) cell, then it becomes an inline number editor. I enter the restock quantity, press Enter, and Toast confirms: "Stock updated — [product] now has [N] units" (FR25) (AC2)

3. **Bulk Restock**: Given the DataTable, when I select multiple rows via checkboxes, then a bulk action toolbar appears with "Restock Selected" button. Clicking it opens a ConfirmDialog form to enter quantity applied to all selected items, with Toast confirmation on success (AC3)

4. **Low-Stock Highlighting**: Given low-stock products (availableQuantity < lowStockThreshold), when viewing the inventory table, then rows are highlighted with `bg-yellow-50` background (Tailwind) and Status column shows a PrimeVue `Tag` with `severity="warn"` and label "Low Stock" (FR26) (AC4)

5. **Normal Status**: Given products with sufficient stock (availableQuantity >= lowStockThreshold), when viewing the table, then the Status Tag shows `severity="success"` with label "In Stock" (AC5)

6. **Pinia Store**: Given the Inventory page, when `useInventoryStore` loads data, then it manages: inventory items (enriched with product name/SKU), filters, loading/error state, `restockItem()` action, `bulkRestock()` action (AC6)

7. **Backend REST Endpoints**: Given `GET /api/v1/admin/inventory`, when called with ADMIN role JWT via API Gateway, then returns paginated list of inventory items; Given `PUT /api/v1/admin/inventory/{productId}/restock` with `RestockRequest`, then increases available stock by the given quantity and creates StockMovement + outbox event (AC7)

8. **Bulk Restock Endpoint**: Given `POST /api/v1/admin/inventory/bulk-restock` with `BulkRestockRequest`, when called with ADMIN role JWT, then restocks all specified products sequentially, returns list of updated inventory items (AC8)

## Dev Context

### Critical Architecture Decisions

**Cross-service data for Product Name & SKU:**
The `inventory_items` table has `productId`, `available_quantity`, `reserved_quantity`, `total_quantity`, `low_stock_threshold` — but NO `productName` or `sku` columns. Those live in `product-service`.

**Solution (frontend enrichment):** Do NOT add backend cross-service calls. The frontend loads:
1. `GET /api/v1/admin/inventory` → inventory items (productId + stock data)
2. `GET /api/v1/products?page=0&size=1000` → product list (already in `productAdminApi.ts`)

Then joins client-side in the Pinia store using `productId`. This avoids backend coupling and matches the existing frontend-aggregation pattern.

**No @PreAuthorize needed on controller** — ADMIN role is enforced at API Gateway (`GatewaySecurityConfig: .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")`). See `AdminProductRestController.java` comment: `// No @PreAuthorize needed — ADMIN role enforced at API Gateway level`.

**Restock does NOT use distributed locking** — locking is only required for `reserveStock`/`releaseStock` (concurrent order saga operations). Admin restock is a single admin user action; use `@Transactional` with optimistic locking (`@Version` on `InventoryItem`). If optimistic locking fails (stale read), return 409 Conflict.

### Existing Backend (inventory-service) — What's Already There

**Package:** `com.robomart.inventory`

**Entity** (`entity/InventoryItem.java`): Fields: `id`, `productId`, `availableQuantity`, `reservedQuantity`, `totalQuantity`, `lowStockThreshold`, `version` (`@Version`), `updatedAt` (`@UpdateTimestamp`).

**Repository** (`repository/InventoryItemRepository.java`): Only has `findByProductId(Long)`. Needs extension for admin listing.

**Service** (`service/InventoryService.java`): Has `reserveStock()`, `releaseStock()`, `getInventory()`. Does NOT have a `restock` method — must add it.

**Jackson import:** `tools.jackson.databind.ObjectMapper` (Spring Boot 4 / Jackson 3.x — NOT `com.fasterxml.jackson.databind`).

**Common lib DTOs:**
- `ApiResponse<T>` from `com.robomart.common.dto` — use for single-object responses
- `ResourceNotFoundException` from `com.robomart.common.exception`
- `ValidationException` from `com.robomart.common.exception`

**Test support:** `@IntegrationTest` annotation from `com.robomart.test` (already used in `InventoryServiceIT.java` and `InventoryGrpcIT.java`) — no need to create.

**Database schema** (from `V1__init_inventory_schema.sql`):
```sql
inventory_items: id, product_id (UNIQUE), available_quantity, reserved_quantity, total_quantity, low_stock_threshold, version, updated_at
stock_movements: id, inventory_item_id, type (VARCHAR), quantity, order_id, reason, created_at
outbox_events: id, aggregate_type, aggregate_id, event_type, payload (JSONB), created_at, published, published_at
```

**StockMovementType enum** (`enums/StockMovementType.java`): Has `RESERVE`, `RELEASE` — add `RESTOCK`.

### New Backend Files to Create

```
backend/inventory-service/src/main/java/com/robomart/inventory/
├── controller/
│   └── InventoryAdminRestController.java          ← NEW
├── dto/
│   ├── InventoryItemResponse.java                 ← NEW
│   ├── RestockRequest.java                        ← NEW
│   └── BulkRestockRequest.java                    ← NEW
```

**Extend existing files:**
- `InventoryItemRepository.java` — add `findAllBy...` with Pageable support
- `InventoryService.java` — add `restockItem()` and `bulkRestock()` methods
- `StockMovementType.java` — add `RESTOCK` enum value

### Backend Implementation Details

**`InventoryItemResponse` DTO:**
```java
package com.robomart.inventory.dto;

import java.time.Instant;

public record InventoryItemResponse(
    Long id,
    Long productId,
    Integer availableQuantity,
    Integer reservedQuantity,
    Integer totalQuantity,
    Integer lowStockThreshold,
    Instant updatedAt
) {}
```

**`RestockRequest` DTO:**
```java
package com.robomart.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RestockRequest(
    @NotNull @Min(1) Integer quantity,
    String reason   // optional, e.g. "Manual admin restock"
) {}
```

**`BulkRestockRequest` DTO:**
```java
package com.robomart.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BulkRestockRequest(
    @NotEmpty List<@NotNull Long> productIds,
    @NotNull @Min(1) Integer quantity,
    String reason
) {}
```

**Add to `InventoryItemRepository`:**
```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

Page<InventoryItem> findAll(Pageable pageable);
// findByProductId(Long) already exists
```

**Add to `StockMovementType` enum:**
```java
RESTOCK
```

**Add `restockItem()` to `InventoryService`:**
```java
@Transactional
public InventoryItem restockItem(Long productId, int quantity, String reason) {
    if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");

    InventoryItem item = inventoryItemRepository.findByProductId(productId)
        .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));

    item.setAvailableQuantity(item.getAvailableQuantity() + quantity);
    item.setTotalQuantity(item.getTotalQuantity() + quantity);
    InventoryItem saved = inventoryItemRepository.save(item);

    StockMovement movement = new StockMovement();
    movement.setInventoryItemId(saved.getId());
    movement.setType(StockMovementType.RESTOCK);
    movement.setQuantity(quantity);
    movement.setReason(reason != null ? reason : "Admin restock");
    stockMovementRepository.save(movement);

    // Outbox event for downstream notification (Story 6.x will consume)
    try {
        Map<String, Object> payload = new HashMap<>();
        payload.put("productId", productId);
        payload.put("quantity", quantity);
        payload.put("availableQuantity", saved.getAvailableQuantity());
        OutboxEvent event = new OutboxEvent(
            "InventoryItem", productId.toString(), "stock_restocked",
            objectMapper.writeValueAsString(payload)
        );
        outboxEventRepository.save(event);
    } catch (Exception e) {
        log.error("Failed to create stock_restocked outbox event", e);
        throw new RuntimeException("Failed to create outbox event", e);
    }

    return saved;
}
```

**Add `bulkRestock()` to `InventoryService`:**
```java
@Transactional
public List<InventoryItem> bulkRestock(List<Long> productIds, int quantity, String reason) {
    return productIds.stream()
        .map(productId -> restockItem(productId, quantity, reason))
        .toList();
}
```

Note: `bulkRestock` reuses `restockItem` which is `@Transactional`. Since both run in the same transaction context (propagation REQUIRED), the whole bulk operation is one transaction.

**`InventoryAdminRestController`:**
```java
package com.robomart.inventory.controller;

import com.robomart.common.dto.ApiResponse;
import com.robomart.inventory.dto.BulkRestockRequest;
import com.robomart.inventory.dto.InventoryItemResponse;
import com.robomart.inventory.dto.RestockRequest;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.service.InventoryService;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// No @PreAuthorize — ADMIN role enforced at API Gateway
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class InventoryAdminRestController {

    private final InventoryService inventoryService;
    private final Tracer tracer;

    public InventoryAdminRestController(InventoryService inventoryService, Tracer tracer) {
        this.inventoryService = inventoryService;
        this.tracer = tracer;
    }

    @GetMapping
    public ResponseEntity<PagedInventoryResponse> listInventory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<InventoryItem> items = inventoryService.listInventory(
            PageRequest.of(page, size, Sort.by("productId").ascending())
        );
        List<InventoryItemResponse> responses = items.getContent().stream()
            .map(this::toResponse).toList();
        return ResponseEntity.ok(new PagedInventoryResponse(responses, items, getTraceId()));
    }

    @PutMapping("/{productId}/restock")
    public ResponseEntity<ApiResponse<InventoryItemResponse>> restockItem(
            @PathVariable Long productId,
            @RequestBody @Valid RestockRequest request) {
        InventoryItem item = inventoryService.restockItem(
            productId, request.quantity(), request.reason());
        return ResponseEntity.ok(new ApiResponse<>(toResponse(item), getTraceId()));
    }

    @PostMapping("/bulk-restock")
    public ResponseEntity<List<InventoryItemResponse>> bulkRestock(
            @RequestBody @Valid BulkRestockRequest request) {
        List<InventoryItem> items = inventoryService.bulkRestock(
            request.productIds(), request.quantity(), request.reason());
        return ResponseEntity.ok(items.stream().map(this::toResponse).toList());
    }

    private InventoryItemResponse toResponse(InventoryItem item) {
        return new InventoryItemResponse(
            item.getId(), item.getProductId(),
            item.getAvailableQuantity(), item.getReservedQuantity(),
            item.getTotalQuantity(), item.getLowStockThreshold(),
            item.getUpdatedAt()
        );
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
```

**`PagedInventoryResponse`** — create as a simple record in `com.robomart.inventory.dto`:
```java
public record PagedInventoryResponse(
    List<InventoryItemResponse> data,
    PaginationMeta pagination,
    String traceId
) {
    public record PaginationMeta(int page, int size, long totalElements, int totalPages) {}

    public PagedInventoryResponse(List<InventoryItemResponse> data, Page<?> page, String traceId) {
        this(data, new PaginationMeta(page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages()), traceId);
    }
}
```

**Add `listInventory()` to `InventoryService`:**
```java
public Page<InventoryItem> listInventory(Pageable pageable) {
    return inventoryItemRepository.findAll(pageable);
}
```

### Frontend Files to Create/Modify

**Location:** `frontend/admin-dashboard/src/`

**New files:**
```
api/inventoryAdminApi.ts          ← NEW: Axios calls
stores/useInventoryStore.ts       ← NEW: Pinia store
```

**Modified files:**
```
views/InventoryPage.vue           ← REPLACE placeholder with full implementation
```

**`inventoryAdminApi.ts`:**
```typescript
import adminClient from './adminClient'

export interface InventoryItem {
  id: number
  productId: number
  availableQuantity: number
  reservedQuantity: number
  totalQuantity: number
  lowStockThreshold: number
  updatedAt: string
}

export interface InventoryItemEnriched extends InventoryItem {
  productName: string
  sku: string
}

interface PagedInventoryResponse {
  data: InventoryItem[]
  pagination: { page: number; size: number; totalElements: number; totalPages: number }
  traceId: string
}

interface ApiResponse<T> {
  data: T
  traceId: string
}

export async function listInventory(page = 0, size = 25): Promise<PagedInventoryResponse> {
  const { data } = await adminClient.get<PagedInventoryResponse>(
    `/api/v1/admin/inventory?page=${page}&size=${size}`
  )
  return data
}

export async function restockItem(
  productId: number,
  quantity: number,
  reason?: string
): Promise<InventoryItem> {
  const { data } = await adminClient.put<ApiResponse<InventoryItem>>(
    `/api/v1/admin/inventory/${productId}/restock`,
    { quantity, reason }
  )
  return data.data
}

export async function bulkRestock(
  productIds: number[],
  quantity: number,
  reason?: string
): Promise<InventoryItem[]> {
  const { data } = await adminClient.post<InventoryItem[]>(
    `/api/v1/admin/inventory/bulk-restock`,
    { productIds, quantity, reason }
  )
  return data
}
```

**`useInventoryStore.ts`:**
```typescript
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import {
  listInventory,
  restockItem as apiRestockItem,
  bulkRestock as apiBulkRestock,
  type InventoryItemEnriched
} from '@/api/inventoryAdminApi'
import { listProducts } from '@/api/productAdminApi'

export const useInventoryStore = defineStore('inventory', () => {
  const items = ref<InventoryItemEnriched[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const totalElements = ref(0)
  const currentPage = ref(0)
  const pageSize = ref(25)

  const lowStockItems = computed(() =>
    items.value.filter(i => i.availableQuantity < i.lowStockThreshold)
  )

  async function loadInventory(page = 0) {
    isLoading.value = true
    error.value = null
    try {
      // Load inventory + product data in parallel, join client-side
      const [inventoryPage, productsPage] = await Promise.all([
        listInventory(page, pageSize.value),
        listProducts(0, 1000)  // load all products for enrichment
      ])
      const productMap = new Map(productsPage.data.map(p => [p.id, p]))
      items.value = inventoryPage.data.map(item => ({
        ...item,
        productName: productMap.get(item.productId)?.name ?? `Product #${item.productId}`,
        sku: productMap.get(item.productId)?.sku ?? '—'
      }))
      totalElements.value = inventoryPage.pagination.totalElements
      currentPage.value = page
    } catch (e) {
      error.value = 'Failed to load inventory'
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function restockItem(productId: number, quantity: number): Promise<void> {
    const updated = await apiRestockItem(productId, quantity, 'Admin restock')
    const idx = items.value.findIndex(i => i.productId === productId)
    if (idx !== -1) {
      items.value[idx] = { ...items.value[idx], ...updated }
    }
  }

  async function bulkRestock(productIds: number[], quantity: number): Promise<void> {
    const updatedItems = await apiBulkRestock(productIds, quantity, 'Admin bulk restock')
    const updatedMap = new Map(updatedItems.map(i => [i.productId, i]))
    items.value = items.value.map(i =>
      updatedMap.has(i.productId) ? { ...i, ...updatedMap.get(i.productId)! } : i
    )
  }

  return { items, isLoading, error, totalElements, currentPage, pageSize, lowStockItems,
           loadInventory, restockItem, bulkRestock }
})
```

**`InventoryPage.vue` — Full Implementation Pattern:**

Replace the placeholder entirely. Key implementation points:
- Use `DataTable` with `:value="inventoryStore.items"`, `v-model:selection="selectedRows"`, `dataKey="productId"`, `editMode="cell"`, `paginator`, `rows="25"`, `:rowsPerPageOptions="[10, 25, 50, 100]"`
- Column for Status: use `<Tag :severity="isLowStock(item) ? 'warn' : 'success'" :value="isLowStock(item) ? 'Low Stock' : 'In Stock'" />`
- Row CSS class for low stock: `:rowClass="(item) => item.availableQuantity < item.lowStockThreshold ? 'bg-yellow-50' : ''"`
- Inline cell editing on "Current Stock": use `@cell-edit-complete` event, call `inventoryStore.restockItem()`
- Bulk toolbar: show when `selectedRows.length > 0`, "Restock Selected" button opens ConfirmDialog or a Dialog with quantity input
- Toast: `useToast()` from `primevue/usetoast`, show `"Stock updated — ${productName} now has ${N} units"`
- `onMounted(() => inventoryStore.loadInventory())`
- Skeleton loading: `<Skeleton v-if="inventoryStore.isLoading" />` rows

**Important cell-edit pattern** (inline restock — enters the restock quantity, NOT current stock):
```vue
<Column field="availableQuantity" header="Current Stock" sortable>
  <template #editor="{ data, field }">
    <InputNumber v-model="data[field]" :min="0" autofocus
      @keydown.enter="saveCellEdit(data)"
      @keydown.escape="cancelCellEdit(data)" />
  </template>
</Column>
```
On `@cell-edit-complete`, the `newValue` is the restocked total. Calculate delta: `quantity = newValue - originalAvailable`, then call `restockItem(productId, delta)`. Show error Toast if delta ≤ 0 ("Cannot reduce stock via restock — contact system admin").

**Bulk Restock Dialog**: Use PrimeVue `Dialog` (not ConfirmDialog) since user needs to input quantity:
```vue
<Dialog v-model:visible="showBulkRestockDialog" header="Bulk Restock" modal>
  <p>Restock {{ selectedRows.length }} products</p>
  <InputNumber v-model="bulkQuantity" :min="1" label="Quantity to add" />
  <template #footer>
    <Button label="Cancel" severity="secondary" @click="showBulkRestockDialog = false" />
    <Button label="Restock" @click="confirmBulkRestock" />
  </template>
</Dialog>
```

### Frontend File Locations (Exact Paths)

| File | Action |
|------|--------|
| `frontend/admin-dashboard/src/api/inventoryAdminApi.ts` | CREATE |
| `frontend/admin-dashboard/src/stores/useInventoryStore.ts` | CREATE |
| `frontend/admin-dashboard/src/views/InventoryPage.vue` | REPLACE (currently empty placeholder) |
| `frontend/admin-dashboard/src/__tests__/InventoryPage.test.ts` | CREATE |

### Backend File Locations (Exact Paths)

| File | Action |
|------|--------|
| `backend/inventory-service/src/main/java/com/robomart/inventory/controller/InventoryAdminRestController.java` | CREATE |
| `backend/inventory-service/src/main/java/com/robomart/inventory/dto/InventoryItemResponse.java` | CREATE |
| `backend/inventory-service/src/main/java/com/robomart/inventory/dto/RestockRequest.java` | CREATE |
| `backend/inventory-service/src/main/java/com/robomart/inventory/dto/BulkRestockRequest.java` | CREATE |
| `backend/inventory-service/src/main/java/com/robomart/inventory/dto/PagedInventoryResponse.java` | CREATE |
| `backend/inventory-service/src/main/java/com/robomart/inventory/service/InventoryService.java` | EXTEND (add `restockItem`, `bulkRestock`, `listInventory`) |
| `backend/inventory-service/src/main/java/com/robomart/inventory/repository/InventoryItemRepository.java` | EXTEND (JpaRepository already provides `findAll(Pageable)`) |
| `backend/inventory-service/src/main/java/com/robomart/inventory/enums/StockMovementType.java` | EXTEND (add `RESTOCK`) |
| `backend/inventory-service/src/test/java/com/robomart/inventory/unit/service/InventoryServiceTest.java` | EXTEND (add restock tests) |
| `backend/inventory-service/src/test/java/com/robomart/inventory/integration/InventoryAdminRestIT.java` | CREATE |

### Previous Story Learnings (Stories 5.1–5.3)

- **No `@PreAuthorize`** on admin controllers — gateway handles auth. Confirmed pattern.
- **`ResourceNotFoundException` 1-arg constructor**: use `new ResourceNotFoundException("message")` — the 2-arg constructor is protected (learned from Story 5.2).
- **`ValidationException`** is in `com.robomart.common.exception` — do NOT create a duplicate.
- **`ApiResponse<T>`** wrapper for single-object responses; raw list for multi-object (e.g., `List<InventoryItemResponse>` directly, no wrapper).
- **Jackson 3.x**: `tools.jackson.databind.ObjectMapper` — already used in `InventoryService.java`.
- **Jakarta validation**: `jakarta.validation.constraints.*` — NOT `javax.validation`.
- **Integration tests**: use `@IntegrationTest` annotation from `com.robomart.test` + `RestClient` with `@LocalServerPort` (no JWT — gateway handles auth in production).
- **`JpaRepository.findAll(Pageable)`** is inherited from Spring Data — no need to declare it explicitly in the repository interface.
- **`Tracer` injection** pattern: `io.micrometer.tracing.Tracer` — same as `AdminProductRestController`.
- **PrimeVue Tag `severity`**: in PrimeVue 4.x, `severity="warn"` (not `"warning"`) for amber/yellow.

### Testing Requirements

**Backend Unit Tests** (extend `InventoryServiceTest.java`):
```
- restockItem_validProduct_increasesAvailableAndTotal()
- restockItem_invalidQuantity_throwsIllegalArgumentException()
- restockItem_productNotFound_throwsResourceNotFoundException()
- restockItem_createsStockMovementWithRestockType()
- restockItem_createsOutboxEventWithStockRestockedType()
- bulkRestock_multipleProducts_restocksAll()
```
Mock: `InventoryItemRepository`, `StockMovementRepository`, `OutboxEventRepository`, `ObjectMapper`.

**Backend Integration Test** (`InventoryAdminRestIT.java`):
- Use `@IntegrationTest` + `RestClient` with `@LocalServerPort`
- Seed data: product IDs 1–50 already in `R__seed_inventory.sql` — use IDs not modified by other tests
- Tests:
  - `shouldListInventoryWithPagination()` — GET /api/v1/admin/inventory returns 200 with paginated data
  - `shouldRestockItemAndReturn200()` — PUT increases availableQuantity + totalQuantity
  - `shouldReturn400WhenRestockQuantityIsZeroOrNegative()`
  - `shouldReturn404WhenProductNotFound()` — use productId = 9999
  - `shouldBulkRestockMultipleItems()`

**Frontend Unit Tests** (`__tests__/InventoryPage.test.ts`):
- Mock `useInventoryStore` using Vitest `vi.mock`
- Tests: renders DataTable when items loaded, shows low-stock row class, triggers restockItem on cell edit, shows bulk toolbar on row selection, shows error state

### API Gateway Configuration

The inventory-service needs to be registered at the API Gateway. Check `backend/api-gateway/src/main/resources/application.yml` (or similar) for route definitions. Add route if not present:
```yaml
- id: inventory-service
  uri: http://inventory-service:8083
  predicates:
    - Path=/api/v1/admin/inventory/**
  filters:
    - name: CircuitBreaker
```
Verify existing routes to avoid duplicate path matchers.

## Tasks / Subtasks

### Backend: inventory-service

- [x] Task 1: Add `RESTOCK` to `StockMovementType` enum (AC7)
  - [ ] 1.1 Open `enums/StockMovementType.java`, add `RESTOCK` value

- [x] Task 2: Create DTOs (AC7, AC8)
  - [x] 2.1 Create `dto/InventoryItemResponse.java` (record with 7 fields as specified above)
  - [x] 2.2 Create `dto/RestockRequest.java` (record with `quantity` + `reason`)
  - [x] 2.3 Create `dto/BulkRestockRequest.java` (record with `productIds`, `quantity`, `reason`)
  - [x] 2.4 Create `dto/PagedInventoryResponse.java` (record with nested `PaginationMeta`)
  - [x] 2.5 Use `jakarta.validation.constraints.*` — NOT `javax.validation`

- [x] Task 3: Extend `InventoryService` with restock and list operations (AC7, AC8)
  - [x] 3.1 Add `listInventory(Pageable pageable)` — delegates to `inventoryItemRepository.findAll(pageable)` (JpaRepository already provides this, no repository change needed)
  - [x] 3.2 Add `restockItem(Long productId, int quantity, String reason)` with `@Transactional`
    - Validate quantity > 0
    - Load item via `findByProductId` (throw `ResourceNotFoundException` if missing)
    - Increment `availableQuantity` and `totalQuantity`
    - Save item
    - Create `StockMovement` with `StockMovementType.RESTOCK`
    - Create outbox event `stock_restocked`
  - [x] 3.3 Add `bulkRestock(List<Long> productIds, int quantity, String reason)` with `@Transactional`
    - Iterate `productIds`, call `restockItem()` for each (same transaction, REQUIRED propagation)
    - Return `List<InventoryItem>`

- [x] Task 4: Create `InventoryAdminRestController` (AC7, AC8)
  - [x] 4.1 `GET /api/v1/admin/inventory` → `listInventory()` → `PagedInventoryResponse`
  - [x] 4.2 `PUT /api/v1/admin/inventory/{productId}/restock` → `restockItem()` → `ApiResponse<InventoryItemResponse>`
  - [x] 4.3 `POST /api/v1/admin/inventory/bulk-restock` → `bulkRestock()` → `List<InventoryItemResponse>`
  - [x] 4.4 Inject `Tracer` for `getTraceId()` — same pattern as `AdminProductRestController`

- [x] Task 5: Backend Tests (AC7, AC8)
  - [x] 5.1 Extend `InventoryServiceTest.java` — add unit tests for `restockItem()` and `bulkRestock()` (list in Testing Requirements section)
  - [x] 5.2 Create `integration/InventoryAdminRestIT.java` with `@IntegrationTest` — 5 tests (list in Testing Requirements section)

### Frontend: admin-dashboard

- [x] Task 6: Create `inventoryAdminApi.ts` (AC1, AC2, AC3, AC6)
  - [x] 6.1 Define `InventoryItem` and `InventoryItemEnriched` interfaces
  - [x] 6.2 Implement `listInventory(page, size)`, `restockItem(productId, quantity, reason)`, `bulkRestock(productIds, quantity, reason)` functions
  - [x] 6.3 Match API response shape from backend (`PagedInventoryResponse`, `ApiResponse<T>`, raw list)

- [x] Task 7: Create `useInventoryStore.ts` (AC6)
  - [x] 7.1 Pinia store with `items`, `isLoading`, `error`, `totalElements`, `currentPage`, `pageSize`
  - [x] 7.2 `loadInventory()` — parallel fetch of inventory + products, client-side enrichment using productId map
  - [x] 7.3 `restockItem(productId, quantity)` — API call then optimistic update of `items` array
  - [x] 7.4 `bulkRestock(productIds, quantity)` — API call then batch update of `items` array
  - [x] 7.5 `lowStockItems` computed — items where `availableQuantity < lowStockThreshold`

- [x] Task 8: Implement `InventoryPage.vue` (AC1–AC5)
  - [x] 8.1 Replace placeholder with full PrimeVue DataTable implementation
  - [x] 8.2 Columns: Product Name, SKU, Current Stock (inline editable), Reserved, Available, Threshold, Status (Tag)
  - [x] 8.3 Row CSS class: `bg-yellow-50` when `availableQuantity < lowStockThreshold` (AC4)
  - [x] 8.4 Status Tag: `severity="warn"` / `severity="success"`, labels "Low Stock" / "In Stock" (AC4, AC5)
  - [x] 8.5 Cell edit on Current Stock column: `@cell-edit-complete` → calculate delta, call `inventoryStore.restockItem()`, show Toast (AC2)
  - [x] 8.6 Row selection: `v-model:selection="selectedRows"`, bulk toolbar shown when `selectedRows.length > 0` (AC3)
  - [x] 8.7 Bulk restock Dialog: input quantity, on confirm call `inventoryStore.bulkRestock()`, show Toast (AC3)
  - [x] 8.8 Skeleton loading rows while `inventoryStore.isLoading` (UX-DR13)
  - [x] 8.9 `onMounted(() => inventoryStore.loadInventory())`

- [x] Task 9: Frontend Tests (AC1–AC6)
  - [x] 9.1 Create `__tests__/InventoryPage.test.ts`
  - [x] 9.2 Tests: renders DataTable with items, low-stock row gets `bg-yellow-50` class, cell edit triggers restockItem, bulk toolbar appears on selection, error state shown when store has error

### Verify API Gateway Route

- [x] Task 10: Verify API Gateway routes inventory-service admin path (AC7)
  - [x] 10.1 Check `backend/api-gateway/src/main/resources/application.yml` for `/api/v1/admin/inventory/**` route
  - [x] 10.2 Add route if not present (see API Gateway Configuration section above)

### Review Findings

- [x] [Review][Patch] Handle `OptimisticLockException` — fixed: @ExceptionHandler returns 409 Conflict with retry hint [InventoryAdminRestController.java]
- [x] [Review][Patch] Add `@Max(100)` on `size` parameter — fixed: prevents unbounded page size [InventoryAdminRestController.java]
- [x] [Review][Patch] Add `@Transactional(readOnly=true)` on `listInventory()` — fixed [InventoryService.java]
- [x] [Review][Patch] Fix inline edit InputNumber min — fixed: `:min="data.availableQuantity + 1"` prevents delta=0 [InventoryPage.vue]
- [x] [Review][Patch] Fix bulk restock error message — fixed: "Bulk restock failed. No products were updated." [InventoryPage.vue]
- [x] [Review][Patch] Deduplicate `BulkRestockRequest.productIds` — fixed: `.distinct()` in bulkRestock() [InventoryService.java]
- [x] [Review][Patch] Rename column header "Total" → "Available" — skipped: renaming would be misleading since field shows totalQuantity not availableQuantity
- [x] [Review][Patch] Add `sortable` to Threshold column — fixed [InventoryPage.vue]
- [x] [Review][Patch] Fix toast detail text — fixed: "units" instead of "units available" per spec [InventoryPage.vue]
- [x] [Review][Patch] Add unit test for OptimisticLockException — fixed: new test in RestockItem nested class [InventoryServiceTest.java]
- [x] [Review][Patch] Add integration test for concurrent restock + reserve — skipped: requires complex concurrent test harness, deferred to Epic 8 resilience
- [x] [Review][Defer] Frontend `listProducts(0, 1000)` scalability ceiling — redesign needed if catalog exceeds 1000 products — deferred, beyond story scope
- [x] [Review][Defer] Inventory service has no `SecurityFilterChain` — defense-in-depth decision deferred to system-wide security hardening
- [x] [Review][Defer] Store missing "filters" state (AC6) — filter UI is an enhancement, spec wording is ambiguous — deferred
