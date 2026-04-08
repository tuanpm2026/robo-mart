# Story 5.5: Implement Admin Order Management

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an admin,
I want to view and manage all orders with status filtering and detailed order views,
So that I can monitor and process customer orders efficiently.

## Acceptance Criteria

1. **Orders DataTable** (FR44): Given the Orders page in Admin Dashboard, when I navigate to it, then I see a PrimeVue DataTable with columns: Order #, Customer (userId), Date (createdAt), Items Count, Total, Status (PrimeVue Tag), Actions — sorted by date descending, with 25 rows default pagination. Payment state is implicitly represented by OrderStatus (CONFIRMED+ means paid). (AC1)

2. **Status Filter** (FR44): Given the Orders DataTable, when I use the Status filter (multi-select PrimeVue Dropdown), then orders are filtered server-side to show only selected statuses (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED) (AC2)

3. **Order Detail Slide-Over** (FR45): Given an order row, when I click "View" in the Actions column, then a slide-over panel (PrimeVue Drawer/Sidebar) opens showing: order items (product name, quantity, unit price, subtotal), customer info (userId), shipping address, payment status, order timeline (PrimeVue Timeline with status history and timestamps) (AC3)

4. **OrderStateMachine Admin Variant** (UX-DR5): Given the order detail slide-over, when viewing the order timeline, then technical Saga states are available on hover (e.g., hover "Processing payment" shows "PAYMENT_PROCESSING"), compact layout, saga step detail visible (AC4)

5. **Inline Status Update**: Given the Orders DataTable, when I click an inline status Dropdown on an order row, then I can update the status (e.g., CONFIRMED -> SHIPPED) with Toast confirmation. Only allow transitions: CONFIRMED -> SHIPPED, SHIPPED -> DELIVERED (AC5)

6. **Pinia Store**: Given the Orders page, when `useOrderAdminStore` loads data, then it manages: orders list, status filter, pagination, loading/error state, `loadOrders()` action, `updateOrderStatus()` action, `getOrderDetail()` action (AC6)

7. **Backend REST Endpoints**: (AC7)
   - `GET /api/v1/admin/orders?page=0&size=25&status=CONFIRMED,SHIPPED` — returns paginated orders with optional status filter
   - `GET /api/v1/admin/orders/{orderId}` — returns full order detail with items and status history
   - `PUT /api/v1/admin/orders/{orderId}/status` with `UpdateOrderStatusRequest` — updates order status (admin-only transitions)

## Tasks / Subtasks

- [x] Task 1: Create `OrderAdminRestController` backend (AC: #7)
  - [x] 1.1 Add admin query methods to `OrderService` — `getAllOrders(Pageable, List<OrderStatus>)`, `getOrderDetail(Long)`, `updateOrderStatus(Long, OrderStatus)`
  - [x] 1.2 Add `findAll(Pageable)` and `findByStatusIn(List<OrderStatus>, Pageable)` to `OrderRepository`
  - [x] 1.3 Create `OrderAdminRestController` at `/api/v1/admin/orders` with 3 endpoints
  - [x] 1.4 Create DTOs: `AdminOrderSummaryResponse` (reuse `OrderSummaryResponse` fields + userId), `AdminOrderDetailResponse` (OrderDetailResponse + userId), `UpdateOrderStatusRequest`
- [x] Task 2: Write backend tests (AC: #7)
  - [x] 2.1 Unit tests for `OrderService` admin methods (shouldListAllOrdersWhen..., shouldFilterByStatusWhen..., shouldUpdateStatusWhen..., shouldRejectInvalidTransitionWhen...)
  - [x] 2.2 Integration test `OrderAdminRestIT` (list, filter, detail, status update)
- [x] Task 3: Create `orderAdminApi.ts` frontend API module (AC: #6)
  - [x] 3.1 Implement `listOrders(page, size, statuses?)`, `getOrderDetail(orderId)`, `updateOrderStatus(orderId, status)`
- [x] Task 4: Create `useOrderAdminStore.ts` Pinia store (AC: #6)
  - [x] 4.1 State: orders, selectedOrder, statusFilter, pagination, isLoading, error
  - [x] 4.2 Actions: loadOrders(), getOrderDetail(id), updateOrderStatus(id, status)
- [x] Task 5: Implement OrdersPage.vue DataTable (AC: #1, #2, #5)
  - [x] 5.1 Replace placeholder with DataTable — columns, pagination, sorting
  - [x] 5.2 Status multi-select filter with server-side filtering
  - [x] 5.3 Inline status Dropdown for admin transitions (CONFIRMED->SHIPPED, SHIPPED->DELIVERED)
  - [x] 5.4 Status Tag with severity colors (success=DELIVERED, info=CONFIRMED, warn=PENDING, danger=CANCELLED)
- [x] Task 6: Implement Order Detail slide-over panel (AC: #3, #4)
  - [x] 6.1 PrimeVue Drawer/Sidebar component with order items table
  - [x] 6.2 PrimeVue Timeline for status history with human-readable labels + technical hover
  - [x] 6.3 Customer info, shipping address, payment status display
- [x] Task 7: Write frontend tests (AC: #1-#6)
  - [x] 7.1 OrdersPage.test.ts — renders DataTable, filters, status update, detail slide-over

## Dev Notes

### Critical Architecture Decisions

**No @PreAuthorize on admin controller** — ADMIN role enforced at API Gateway level (`GatewaySecurityConfig: .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")`). Same pattern as `AdminProductRestController` and `InventoryAdminRestController`.

**API Gateway route already configured** — `RouteConfig.java:42-44` already has `.route("admin-orders", r -> r.path("/api/v1/admin/orders/**").uri(orderServiceUri))`.

**Reuse existing DTOs where possible** — `OrderItemResponse`, `OrderStatusHistoryResponse` already exist in `com.robomart.order.web`. Create new admin-specific response DTOs:
- `AdminOrderSummaryResponse` — adds `userId` field (customer-facing `OrderSummaryResponse` doesn't expose userId)
- `AdminOrderDetailResponse` — adds `userId` field (customer-facing `OrderDetailResponse` doesn't expose userId)

**Status update transitions — admin only allows:**
- CONFIRMED -> SHIPPED (admin marks as shipped)
- SHIPPED -> DELIVERED (admin marks as delivered)
- Do NOT allow admin to set PENDING, CANCELLED, or saga states (INVENTORY_RESERVING, PAYMENT_PROCESSING, PAYMENT_REFUNDING, INVENTORY_RELEASING) — those are system-managed transitions.

**Status filter shows user-visible statuses only:**
- PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
- Hide saga internal states (INVENTORY_RESERVING, PAYMENT_PROCESSING, PAYMENT_REFUNDING, INVENTORY_RELEASING) from filter dropdown

**Human-readable status labels for Timeline (UX-DR5):**
- PENDING -> "Order placed"
- INVENTORY_RESERVING -> "Reserving items..."
- PAYMENT_PROCESSING -> "Processing payment..."
- CONFIRMED -> "Order confirmed"
- SHIPPED -> "Shipped"
- DELIVERED -> "Delivered"
- CANCELLED -> "Cancelled"
- PAYMENT_REFUNDING -> "Refunding payment..."
- INVENTORY_RELEASING -> "Releasing inventory..."
- Hover on any label shows raw enum value (e.g., hover "Processing payment..." shows `PAYMENT_PROCESSING`)

### Existing Backend (order-service) — What's Already There

**Package:** `com.robomart.order`

**Entity** (`entity/Order.java`): Fields: `id` (from BaseEntity), `userId`, `totalAmount`, `status` (OrderStatus enum), `shippingAddress`, `reservationId`, `paymentId`, `cancellationReason`, `version` (`@Version`), `items` (OneToMany), `statusHistory` (OneToMany), `createdAt`/`updatedAt` (from BaseEntity).

**OrderStatus enum** (`enums/OrderStatus.java`): `PENDING`, `INVENTORY_RESERVING`, `PAYMENT_PROCESSING`, `CONFIRMED`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `PAYMENT_REFUNDING`, `INVENTORY_RELEASING`

**Repository** (`repository/OrderRepository.java`): Has `findByUserId(String, Pageable)`, `findByStatusIn(List<OrderStatus>)`. Need to add paginated variants.

**OrderItemRepository** (`repository/OrderItemRepository.java`): Has `findByOrderId(Long)`, `countsByOrderIds(List<Long>)`.

**OrderStatusHistoryRepository**: Has `findByOrderIdOrderByChangedAtAsc(Long)`.

**Service** (`service/OrderService.java`): Has `createOrder()`, `getOrder()`, `getOrdersByUser()`, `getOrderForUser()`, `cancelOrder()`. Need to add admin-specific methods.

**Existing DTOs in `com.robomart.order.web`:**
- `OrderSummaryResponse(id, createdAt, totalAmount, status, itemCount, cancellationReason)` — missing `userId`
- `OrderDetailResponse(id, createdAt, updatedAt, totalAmount, status, shippingAddress, cancellationReason, items, statusHistory)` — can reuse for admin detail (already includes items + history)
- `OrderItemResponse(productId, productName, quantity, unitPrice, subtotal)`
- `OrderStatusHistoryResponse(status, changedAt)`

**Controller** (`web/OrderRestController.java`): Customer-facing at `/api/v1/orders`. Do NOT modify this. Create separate `OrderAdminRestController`.

**Jackson import:** `tools.jackson.databind.ObjectMapper` (Spring Boot 4 / Jackson 3.x — NOT `com.fasterxml.jackson.databind`).

**Common lib DTOs:**
- `ApiResponse<T>` from `com.robomart.common.dto` — for single-object responses
- `PagedResponse<T>` from `com.robomart.common.dto` — for paginated list responses
- `PaginationMeta` from `com.robomart.common.dto` — pagination metadata
- `ResourceNotFoundException` from `com.robomart.common.exception`

**Test support:** `@IntegrationTest` from `com.robomart.test`.

**Database schema** (from `V1__init_order_schema.sql`):
```sql
orders: id, user_id, total_amount, status (VARCHAR), shipping_address, cancellation_reason, version, created_at, updated_at
-- V2 adds: reservation_id, payment_id, cancellation_reason
order_items: id, order_id (FK), product_id, product_name, quantity, unit_price, subtotal, created_at
order_status_history: id, order_id (FK), status (VARCHAR), changed_at
```

### New Backend Files to Create

```
backend/order-service/src/main/java/com/robomart/order/
├── controller/
│   └── OrderAdminRestController.java          ← NEW (follows AdminProductRestController, InventoryAdminRestController convention)
├── web/
│   ├── AdminOrderSummaryResponse.java         ← NEW (adds userId to OrderSummaryResponse)
│   ├── AdminOrderDetailResponse.java          ← NEW (OrderDetailResponse + userId for admin)
│   └── UpdateOrderStatusRequest.java          ← NEW
```

**Test files:**
```
backend/order-service/src/test/java/com/robomart/order/
├── unit/service/OrderServiceAdminTest.java     ← NEW
├── integration/OrderAdminRestIT.java           ← NEW
```

### Backend Implementation Details

**`AdminOrderSummaryResponse` DTO:**
```java
package com.robomart.order.web;

import java.math.BigDecimal;
import java.time.Instant;
import com.robomart.order.enums.OrderStatus;

public record AdminOrderSummaryResponse(
    Long id, String userId, Instant createdAt, BigDecimal totalAmount,
    OrderStatus status, int itemCount, String cancellationReason
) {}
```

**`UpdateOrderStatusRequest` DTO:**
```java
package com.robomart.order.web;

import com.robomart.order.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {}
```

**`AdminOrderDetailResponse` DTO:**
```java
package com.robomart.order.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.robomart.order.enums.OrderStatus;

public record AdminOrderDetailResponse(
    Long id, String userId, Instant createdAt, Instant updatedAt, BigDecimal totalAmount,
    OrderStatus status, String shippingAddress, String cancellationReason,
    List<OrderItemResponse> items, List<OrderStatusHistoryResponse> statusHistory
) {}
```

**Add to `OrderRepository`:**
```java
// findAll(Pageable) already inherited from JpaRepository — do NOT re-declare
Page<Order> findByStatusIn(List<OrderStatus> statuses, Pageable pageable);
// Note: existing findByStatusIn(List<OrderStatus>) returns List — this paginated overload is new
```

**Add admin methods to `OrderService`:**
```java
// List all orders (admin — no userId filter)
public Page<AdminOrderSummaryResponse> getAllOrders(int page, int size, List<OrderStatus> statuses) {
    Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
    Page<Order> orders = (statuses == null || statuses.isEmpty())
        ? orderRepository.findAll(pageable)
        : orderRepository.findByStatusIn(statuses, pageable);
    List<Long> orderIds = orders.getContent().stream().map(Order::getId).toList();
    Map<Long, Long> itemCounts = orderIds.isEmpty() ? Map.of()
        : orderItemRepository.countsByOrderIds(orderIds).stream()
            .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    return orders.map(order -> new AdminOrderSummaryResponse(
        order.getId(), order.getUserId(), order.getCreatedAt(),
        order.getTotalAmount(), order.getStatus(),
        itemCounts.getOrDefault(order.getId(), 0L).intValue(),
        order.getCancellationReason()));
}

// Get order detail for admin (no userId ownership check) — returns AdminOrderDetailResponse with userId
public AdminOrderDetailResponse getOrderDetailForAdmin(Long orderId) {
    Order order = getOrder(orderId);
    List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(orderId);
    // Same mapping as getOrderForUser but: no userId check, returns AdminOrderDetailResponse with userId
}

// Extract helper to avoid duplication with getOrdersByUser():
private Map<Long, Long> buildItemCountMap(List<Long> orderIds) {
    return orderIds.isEmpty() ? Map.of()
        : orderItemRepository.countsByOrderIds(orderIds).stream()
            .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
}

// Admin status update (only CONFIRMED->SHIPPED, SHIPPED->DELIVERED)
private static final Map<OrderStatus, OrderStatus> ADMIN_TRANSITIONS = Map.of(
    OrderStatus.CONFIRMED, OrderStatus.SHIPPED,
    OrderStatus.SHIPPED, OrderStatus.DELIVERED
);

@Transactional
public Order updateOrderStatus(Long orderId, OrderStatus newStatus) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    OrderStatus allowedNext = ADMIN_TRANSITIONS.get(order.getStatus());
    if (allowedNext == null || allowedNext != newStatus) {
        throw new IllegalStateException("Invalid status transition: " + order.getStatus() + " -> " + newStatus);
    }
    order.setStatus(newStatus);
    OrderStatusHistory history = new OrderStatusHistory();
    history.setOrder(order);
    history.setStatus(newStatus);
    history.setChangedAt(Instant.now());
    orderStatusHistoryRepository.save(history);
    return orderRepository.save(order);
}
```

**`OrderAdminRestController`:**
```java
package com.robomart.order.controller;  // NOT web — follows AdminProductRestController pattern

// No @PreAuthorize — ADMIN role enforced at API Gateway
@Validated
@RestController
@RequestMapping("/api/v1/admin/orders")
public class OrderAdminRestController {
    private final OrderService orderService;
    private final Tracer tracer;  // io.micrometer.tracing.Tracer — same as other admin controllers

    // GET /?page=0&size=25&status=CONFIRMED,SHIPPED → PagedResponse<AdminOrderSummaryResponse>
    //   @RequestParam(defaultValue = "25") @Max(100) int size
    //   @RequestParam(required = false) List<OrderStatus> statuses
    // GET /{orderId} → ApiResponse<AdminOrderDetailResponse> (includes userId)
    // PUT /{orderId}/status → ApiResponse<AdminOrderSummaryResponse> with UpdateOrderStatusRequest

    // Include getTraceId() helper — same pattern as AdminProductRestController
    // Include @ExceptionHandler(ObjectOptimisticLockingFailureException.class) → 409 CONFLICT
    //   (Order entity uses @Version — concurrent status update possible)
}
```

### Existing Frontend (admin-dashboard) — What's Already There

**Router** (`router/index.ts`): Route `/admin/orders` already configured pointing to `OrdersPage.vue`.

**OrdersPage.vue** (`views/OrdersPage.vue`): Currently a placeholder with just title and EmptyState.

**Admin API client** (`api/adminClient.ts`): Axios instance with auth interceptor — use as base for `orderAdminApi.ts`.

**Existing admin API pattern** (reference `inventoryAdminApi.ts`):
```typescript
import adminClient from './adminClient'
export function listInventory(page = 0, size = 25) {
  return adminClient.get('/api/v1/admin/inventory', { params: { page, size } })
}
```

**Existing Pinia store pattern** (reference `useInventoryStore.ts`): Composition API with `ref()` state, `computed` getters, async actions, loading/error state management.

**PrimeVue components available:** DataTable, Column, Tag, Button, Dropdown, MultiSelect, Drawer (Sidebar), Timeline, Toast, InputText, Skeleton.

### New Frontend Files to Create

```
frontend/admin-dashboard/src/
├── api/
│   └── orderAdminApi.ts                       ← NEW
├── stores/
│   └── useOrderAdminStore.ts                  ← NEW
├── __tests__/
│   └── OrdersPage.test.ts                     ← NEW
```

**Modify:** `views/OrdersPage.vue` (replace placeholder).

### Frontend Implementation Details

**Status Tag severity mapping:**
```typescript
const statusSeverity: Record<string, string> = {
  PENDING: 'warn',
  INVENTORY_RESERVING: 'info',
  PAYMENT_PROCESSING: 'info',
  CONFIRMED: 'info',
  SHIPPED: 'info',
  DELIVERED: 'success',
  CANCELLED: 'danger',
  PAYMENT_REFUNDING: 'warn',
  INVENTORY_RELEASING: 'warn',
}
```

**Human-readable labels:**
```typescript
const statusLabels: Record<string, string> = {
  PENDING: 'Pending',
  INVENTORY_RESERVING: 'Reserving Items',
  PAYMENT_PROCESSING: 'Processing Payment',
  CONFIRMED: 'Confirmed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  PAYMENT_REFUNDING: 'Refunding Payment',
  INVENTORY_RELEASING: 'Releasing Inventory',
}
```

**Admin-editable statuses for inline dropdown:** Only show dropdown when `order.status === 'CONFIRMED'` (can set to SHIPPED) or `order.status === 'SHIPPED'` (can set to DELIVERED). All other statuses show static Tag only.

**Order Detail slide-over:**
- Use PrimeVue `Drawer` (v4 replacement for Sidebar) or `Sidebar` component, position="right", width ~500px
- Timeline component showing `statusHistory` entries with `statusLabels` mapping
- Hover tooltip showing raw enum value via `v-tooltip` directive

### Testing Standards

**Backend test naming:** `should{Expected}When{Condition}()` (e.g., `shouldReturnPagedOrdersWhenNoFilter()`)
**Assertion library:** AssertJ (`assertThat(...)`)

**IMPORTANT: order-service does NOT use `@IntegrationTest`** — it requires gRPC/Kafka mocks. Copy test setup from existing `OrderCancellationIT.java`:
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)  // from com.robomart.test
class OrderAdminRestIT {
    @MockitoBean private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;
    @MockitoBean private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;
    @MockitoBean @SuppressWarnings("rawtypes") private KafkaTemplate kafkaTemplate;
    @LocalServerPort private int port;
    // Use RestClient with defaultStatusHandler — same as InventoryAdminRestIT
}
```

**Frontend test framework:** Vitest with `@testing-library/vue`

### Project Structure Notes

- Admin controller in `com.robomart.order.controller` package (follows `AdminProductRestController`, `InventoryAdminRestController` convention)
- New DTOs in `com.robomart.order.web` package (co-located with existing order DTOs)
- Admin controller separate from customer controller — do NOT modify `OrderRestController`
- Frontend follows existing admin-dashboard patterns exactly (api/, stores/, views/, __tests__/)
- No cross-service backend calls needed — order-service has all data (items, status history, userId)

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Epic-5, Story 5.5]
- [Source: _bmad-output/planning-artifacts/architecture.md#Order-Service, #Admin-Dashboard, #API-Gateway-Routes]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#UX-DR5, #FR44, #FR45]
- [Source: _bmad-output/implementation-artifacts/5-4-implement-admin-inventory-management.md — Dev Notes]
- [Source: backend/order-service/src/main/java/com/robomart/order/ — existing code patterns]
- [Source: frontend/admin-dashboard/src/ — existing admin UI patterns]

## Senior Developer Review (AI)

**Review Date:** 2026-04-08
**Reviewer:** Claude Opus 4.6 (code-review workflow)
**Review Outcome:** Changes Requested
**Layers:** Blind Hunter, Edge Case Hunter, Acceptance Auditor — all completed

### Review Findings

- [x] [Review][Decision] Status column UX: CONFIRMED/SHIPPED rows show only Select dropdown, no Tag — dismissed (Select already shows current status clearly)
- [x] [Review][Patch] `updateOrderStatus` returns hardcoded `itemCount: 0` — fixed: fetch actual count via orderItemRepository
- [x] [Review][Patch] `statuses` query param joins as comma string instead of repeated params — fixed: use URLSearchParams with repeated keys
- [x] [Review][Patch] `getOrderDetailForAdmin` missing `@Transactional` — fixed: added @Transactional(readOnly = true)
- [x] [Review][Patch] `openDetail` opens slide-over even when loadOrderDetail fails — fixed: wrap in try/catch, show toast on error
- [x] [Review][Patch] `loadOrderDetail` retains stale selectedOrder on error — fixed: clear selectedOrder before fetch, re-throw error
- [x] [Review][Patch] `@org.springframework.transaction.annotation.Transactional` used inline FQ — fixed: added proper import
- [x] [Review][Patch] Store action named `loadOrderDetail` instead of spec-required `getOrderDetail` — fixed: aliased as getOrderDetail in store return
- [x] [Review][Patch] Payment status field missing from Order Detail slide-over — fixed: added derived payment status (Paid/Unpaid/Refunded)
- [x] [Review][Patch] Timeline hover uses native `title` instead of PrimeVue `v-tooltip` directive — fixed: replaced with v-tooltip.top
- [x] [Review][Patch] `formatDate` uses `toLocaleString()` without explicit locale — fixed: use 'en-US' with dateStyle/timeStyle
- [x] [Review][Patch] No guard against concurrent status updates on same order (rapid double-click) — fixed: updatingOrderIds Set guard + disabled Select
- [x] [Review][Patch] Unknown enum in PUT status body returns 500 instead of 400 — fixed: added HttpMessageNotReadableException handler
- [x] [Review][Patch] `formatDate` crashes with null/undefined input — fixed: null guard returning '—'
- [x] [Review][Patch] `shippingAddress` typed as non-nullable `string` in TS but can be null — fixed: typed as string | null
- [x] [Review][Defer] No `@PreAuthorize` defense-in-depth on admin controller — deferred, project-wide decision
- [x] [Review][Defer] Integration tests don't clean up between tests — deferred, pre-existing pattern

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6

### Debug Log References

- Pre-existing test failures in `OrderServiceCancelTest` (7-12 parameterized cases) — not introduced by this story
- Pre-existing test failure in `CommandPalette.test.ts` — not introduced by this story

### Completion Notes List

- Backend: Created `OrderAdminRestController` with 3 REST endpoints (list, detail, status update) following existing admin controller patterns (no @PreAuthorize, gateway-enforced RBAC)
- Backend: Added admin methods to `OrderService` — `getAllOrders()` with status filtering, `getOrderDetailForAdmin()` without userId ownership check, `updateOrderStatus()` with strict CONFIRMED->SHIPPED and SHIPPED->DELIVERED transitions
- Backend: Extracted `buildItemCountMap()` helper to reduce duplication between admin and customer order listing
- Backend: Added paginated `findByStatusIn(List<OrderStatus>, Pageable)` overload to `OrderRepository`
- Backend: Created 3 new DTOs — `AdminOrderSummaryResponse`, `AdminOrderDetailResponse`, `UpdateOrderStatusRequest`
- Backend: 11 unit tests (OrderServiceAdminTest) + 7 integration tests (OrderAdminRestIT) — all passing
- Backend: Exception handlers for `ObjectOptimisticLockingFailureException` (409) and `IllegalStateException` (400) on the admin controller
- Frontend: Created `orderAdminApi.ts` with typed API functions for list/detail/status-update
- Frontend: Created `useOrderAdminStore.ts` Pinia store with composition API, status filtering, pagination, loading/error state
- Frontend: Replaced OrdersPage.vue placeholder with full DataTable implementation — lazy pagination, server-side status filtering via MultiSelect, inline status dropdown for admin transitions, status Tag with severity colors
- Frontend: Order detail slide-over panel using existing `SlideOverPanel` component — order items table, PrimeVue Timeline with human-readable labels and raw enum on hover (title attribute), customer info, shipping address, cancellation reason
- Frontend: 7 Vitest tests (OrdersPage.test.ts) — all passing
- Frontend: TypeScript type-check and production build verified clean

### Change Log

- 2026-04-08: Implemented admin order management — backend REST API, frontend DataTable with filters, order detail slide-over, comprehensive tests

### File List

**New files:**
- backend/order-service/src/main/java/com/robomart/order/controller/OrderAdminRestController.java
- backend/order-service/src/main/java/com/robomart/order/web/AdminOrderSummaryResponse.java
- backend/order-service/src/main/java/com/robomart/order/web/AdminOrderDetailResponse.java
- backend/order-service/src/main/java/com/robomart/order/web/UpdateOrderStatusRequest.java
- backend/order-service/src/test/java/com/robomart/order/unit/service/OrderServiceAdminTest.java
- backend/order-service/src/test/java/com/robomart/order/integration/OrderAdminRestIT.java
- frontend/admin-dashboard/src/api/orderAdminApi.ts
- frontend/admin-dashboard/src/stores/useOrderAdminStore.ts
- frontend/admin-dashboard/src/__tests__/OrdersPage.test.ts

**Modified files:**
- backend/order-service/src/main/java/com/robomart/order/service/OrderService.java
- backend/order-service/src/main/java/com/robomart/order/repository/OrderRepository.java
- frontend/admin-dashboard/src/views/OrdersPage.vue
