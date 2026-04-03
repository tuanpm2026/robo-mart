# Story 4.7: Implement Customer Checkout Flow UI

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a customer,
I want a smooth checkout experience from cart to order confirmation,
So that I can complete my purchase with confidence.

## Acceptance Criteria

1. **Given** the Cart page with items **When** I click "Proceed to Checkout" **Then** if not authenticated, redirect to `/?loginRedirect=/checkout`; if authenticated, navigate to `/checkout` (AC1)

2. **Given** the Checkout page **When** it loads **Then** I see a 4-step PrimeVue Stepper (Cart Review → Shipping Address → Payment → Confirm) with a persistent order summary sidebar on the right showing items, quantities, and total (UX-DR11) (AC2)

3. **Given** Step 1 (Cart Review) **When** displayed **Then** I can edit item quantities (calls `useCartStore.updateItemQuantity`) or remove items, subtotals update in real-time, "Continue" advances to Step 2; redirect to `/cart` if cart becomes empty (AC3)

4. **Given** Step 2 (Shipping Address) **When** I fill out the form **Then** inline validation fires on blur for each field (VeeValidate + Yup), errors show below fields in red, form validates fully before advancing to Step 3 (UX-DR16) (AC4)

5. **Given** Step 3 (Payment) **When** displayed **Then** a Stripe-inspired mock card form; cardNumber (16 digits, space-formatted), expiry (MM/YY, future date), CVV (3-4 digits) validate as-you-type; cardholderName validates on blur (UX-DR11) (AC5)

6. **Given** Step 4 (Confirm) **When** displayed **Then** read-only order summary: items list, shipping address, masked card number (last 4 digits), total; single "Place Order" primary CTA (UX-DR15) (AC6)

7. **Given** "Place Order" clicked **When** Saga processing begins **Then** full-screen overlay with spinner and cycling text: "Creating your order..." → "Reserving items..." → "Processing payment..." (cycles every 1.5s); button disabled; max 3 seconds per NFR3 (UX-DR11) (AC7)

8. **Given** Saga completes successfully **When** order CONFIRMED **Then** navigate to `/order-confirmation/:orderId`; page shows order number, items summary, estimated delivery ("3-5 business days"), "Track Order" primary CTA → `/orders/:id`, "Continue Shopping" → `/` (UX-DR11) (AC8)

9. **Given** Saga fails with `ORDER_PAYMENT_FAILED` **When** 409 returned **Then** overlay dismissed; empathetic error banner on Step 3: "Payment couldn't be processed. Your order is saved — try again or use a different method."; cart preserved (UX-DR11) (AC9)

10. **Given** Saga fails with `ORDER_INVENTORY_FAILED` **When** 409 returned **Then** overlay dismissed; redirect to `/cart?error=out_of_stock`; CartView shows toast: "An item just sold out. Please review your cart." (UX-DR11) (AC10)

11. **Given** `POST /api/v1/orders` backend endpoint **When** called with valid JWT, non-blank items, shippingAddress **Then** creates order, runs Saga synchronously, returns 201 + `ApiResponse<OrderSummaryResponse>`; if Saga fails returns 409 with `error.code` = `ORDER_PAYMENT_FAILED` or `ORDER_INVENTORY_FAILED` (AC11)

## Tasks / Subtasks

- [x] Task 1: Add `POST /api/v1/orders` backend endpoint (AC: 11)
  - [x] 1.1 Create `CreateOrderItemRequest` record: `productId` (String), `productName` (String), `quantity` (int), `unitPrice` (BigDecimal)
  - [x] 1.2 Create `CreateOrderRequest` record: `items` (List\<CreateOrderItemRequest\>), `shippingAddress` (String)
  - [x] 1.3 Add `@PostMapping` to `OrderRestController` — guard null/blank `X-User-Id` (throw `ResourceNotFoundException`); map `CreateOrderItemRequest` → `OrderService.OrderItemRequest`; call `orderService.createOrder(userId, mappedItems, shippingAddress)`; return `ResponseEntity.status(201).body(new ApiResponse<>(summaryResponse, MDC.get("traceId")))`
  - [x] 1.4 Map returned `Order` entity to `OrderSummaryResponse` using existing fields (id, createdAt, totalAmount, status)
  - [x] 1.5 Write `OrderRestControllerCreateTest.java` — 3 tests: POST with valid request (201), POST with null userId (404/ResourceNotFound), POST with saga failure (409)
  - [x] 1.6 Write `OrderServiceCreateTest.java` — 3 tests: createOrder success, createOrder payment failure propagates exception, createOrder inventory failure propagates exception

- [x] Task 2: Add frontend types and `placeOrder` API call (AC: 11)
  - [x] 2.1 Add to `src/types/order.ts`: `CreateOrderItemPayload { productId: string; productName: string; quantity: number; unitPrice: number }`, `PlaceOrderRequest { items: CreateOrderItemPayload[]; shippingAddress: string }`
  - [x] 2.2 Add `placeOrder(request: PlaceOrderRequest): Promise<ApiResponse<OrderSummary>>` to `src/api/orderApi.ts` — `POST /api/v1/orders`, 201 response
  - [x] 2.3 Create `src/types/checkout.ts`: `ShippingFormData { fullName, street, city, state, postalCode, country }`, `PaymentFormData { cardholderName, cardNumber, expiry, cvv }`, `CheckoutError { type: 'PAYMENT_FAILED' | 'INVENTORY_FAILED' | 'UNKNOWN'; message: string }`

- [x] Task 3: Create `useCheckoutStore` Pinia store (AC: 1, 3, 7-10)
  - [x] 3.1 Create `src/stores/useCheckoutStore.ts` (Composition API setup style, same as `useCartStore.ts`)
  - [x] 3.2 State: `currentStep` (ref\<1|2|3|4\>, default 1), `shippingData` (ref\<ShippingFormData | null\>), `paymentData` (ref\<PaymentFormData | null\>), `isPlacingOrder` (ref\<boolean\>), `error` (ref\<CheckoutError | null\>), `sagaMessage` (ref\<string\>)
  - [x] 3.3 Action `nextStep()`: `currentStep.value = Math.min(4, currentStep.value + 1)`
  - [x] 3.4 Action `prevStep()`: `currentStep.value = Math.max(1, currentStep.value - 1)`
  - [x] 3.5 Action `placeOrder(request)`: set `isPlacingOrder=true`, start saga message interval (1.5s cycle through ["Creating your order...", "Reserving items...", "Processing payment..."]), call `orderApi.placeOrder(request)`, on success navigate to `/order-confirmation/${result.data.id}`, on error parse `axiosError.response?.data?.error?.code` → set `CheckoutError`, always clear interval + set `isPlacingOrder=false`
  - [x] 3.6 `$reset()`: reset all state to initial values

- [x] Task 4: Create checkout step components (AC: 2-6)
  - [x] 4.1 Create `src/components/checkout/CheckoutOrderSummary.vue` — accepts `items: CartItem[]` prop; shows item name, qty, subtotal per row; divider; total; sticky sidebar (position: sticky, top: 24px)
  - [x] 4.2 Create `src/components/checkout/StepCartReview.vue` — renders cart items from `useCartStore.items` with qty spinners (InputNumber) and remove buttons; "Continue →" button calls `checkoutStore.nextStep()`; shows empty redirect warning if items.length === 0
  - [x] 4.3 Create `src/components/checkout/StepShippingAddress.vue` — VeeValidate `useForm` + Yup schema; fields: fullName (required, min 2), street (required), city (required), state (required), postalCode (required, /^\d{5}(-\d{4})?$/), country (required, default "US"); on valid submit: `checkoutStore.shippingData = values; checkoutStore.nextStep()`; PrimeVue InputText + `<small class="p-error">{{ errorMessage }}</small>` pattern; "← Back" calls `prevStep()`
  - [x] 4.4 Create `src/components/checkout/StepPayment.vue` — VeeValidate `useForm` + Yup; cardholderName (required), cardNumber (required, strip spaces, 16 digits — display with spaces via formatter computed), expiry (required, MM/YY format, must be future month), cvv (required, 3-4 digits); on valid submit: `checkoutStore.paymentData = values; checkoutStore.nextStep()`; show error banner from `checkoutStore.error` if type === PAYMENT_FAILED
  - [x] 4.5 Create `src/components/checkout/StepConfirm.vue` — display `checkoutStore.shippingData` and masked card (`**** **** **** ${last4}`); items from `useCartStore.items`; "Place Order" button calls `checkoutStore.placeOrder(buildRequest())`; "← Back to Payment" calls `prevStep()`
  - [x] 4.6 Create `src/components/checkout/SagaProgressOverlay.vue` — `v-if="checkoutStore.isPlacingOrder"`; fixed overlay (z-index: 1000), semi-opaque background; centered spinner (PrimeVue ProgressSpinner) + `<p>{{ checkoutStore.sagaMessage }}</p>`; CSS fade transition on sagaMessage change

- [x] Task 5: Create `CheckoutView.vue` (AC: 1-10)
  - [x] 5.1 Create `src/views/CheckoutView.vue` — import and use `useCheckoutStore`, `useCartStore`
  - [x] 5.2 `onMounted`: if `!useAuthStore().isAuthenticated` → `router.push('/')` (router guard handles auth); if `cartStore.items.length === 0` → `router.push('/cart')`
  - [x] 5.3 PrimeVue Stepper in linear mode with `:value="checkoutStore.currentStep"` — StepList + 4 StepPanels
  - [x] 5.4 Two-column grid: stepper takes 2/3 width; `CheckoutOrderSummary` sticky sidebar takes 1/3
  - [x] 5.5 Mount `SagaProgressOverlay` at root level of template (outside stepper columns)
  - [x] 5.6 Watch `checkoutStore.error` — if `type === 'INVENTORY_FAILED'`: `router.push('/cart?error=out_of_stock')`; if `type === 'PAYMENT_FAILED'`: `checkoutStore.currentStep = 3` (handled in StepPayment via error banner)

- [x] Task 6: Create `OrderConfirmationView.vue` (AC: 8)
  - [x] 6.1 Create `src/views/OrderConfirmationView.vue` — fetch order via `useOrderStore().fetchOrder(Number(route.params.orderId))` on mount
  - [x] 6.2 Loading skeleton (3 rows) while fetching
  - [x] 6.3 Success state: large checkmark icon (success-500 color), "Order Confirmed!" heading, order number, items summary table, estimated delivery "3-5 business days"
  - [x] 6.4 "Track Order" PrimeVue Button (primary) → `router.push('/orders/' + orderId)`
  - [x] 6.5 "Continue Shopping" PrimeVue Button (outlined/secondary) → `router.push('/')`
  - [x] 6.6 `$reset()` `useCheckoutStore` on mount (clear checkout state after successful order)

- [x] Task 7: Update router and CartSummary (AC: 1)
  - [x] 7.1 Add to `src/router/index.ts`: `/checkout` → lazy `CheckoutView`, `meta: { requiresAuth: true }`
  - [x] 7.2 Add to `src/router/index.ts`: `/order-confirmation/:orderId` → lazy `OrderConfirmationView`, `meta: { requiresAuth: true }`
  - [x] 7.3 Modify `src/components/cart/CartSummary.vue`: enable "Proceed to Checkout" button (remove `disabled`/coming-soon); handler: `if (authStore.isAuthenticated) router.push('/checkout'); else authStore.login(undefined, undefined, '/checkout')`
  - [x] 7.4 In `CartView.vue` `onMounted` (or watch route): if `route.query.error === 'out_of_stock'`, show toast (severity: 'warn', summary: 'Item unavailable', detail: 'An item just sold out. Please review your cart.', life: 6000)

- [x] Task 8: Write tests (AC: all)
  - [x] 8.1 Backend: `OrderRestControllerTest.java` — 4 new tests: POST valid request (201), POST null userId (404), saga payment failure (409 ORDER_PAYMENT_FAILED), saga inventory failure (409 ORDER_INVENTORY_FAILED)
  - [x] 8.2 Frontend: `useCheckoutStore.spec.ts` (Vitest) — 8 tests: initial state, nextStep/prevStep bounds, setShippingData, placeOrder success → navigation + cart reset, placeOrder PAYMENT_FAILED → error + step 3, placeOrder INVENTORY_FAILED → error, placeOrder UNKNOWN error, $reset
  - [x] 8.3 Frontend: add `placeOrder` test to existing `orderApi.spec.ts` — POST to /api/v1/orders with correct payload

## Dev Notes

### Backend: POST /api/v1/orders — Key Implementation Detail

`OrderService.createOrder(String userId, List<OrderService.OrderItemRequest> items, String shippingAddress)` **already exists** — the only backend work is exposing it via REST.

**CRITICAL — productId type mismatch:** `OrderService.OrderItemRequest.productId` is `String`, but the frontend `CartItem.productId` is `number`. The controller mapping must convert: `String.valueOf(item.productId())`.

**Saga failure behavior:** `OrderService.createOrder` catches saga failures internally, transitions order to CANCELLED, then throws `BusinessRuleException`. The existing `@ExceptionHandler` in the global exception handler maps `BusinessRuleException` → HTTP 409. The error response `code` field will contain the failure reason — verify the exact code strings in `OrderService.java` and document them in the story for the frontend to match (`ORDER_PAYMENT_FAILED`, `ORDER_INVENTORY_FAILED`).

**Endpoint signature:**
```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ResponseEntity<ApiResponse<OrderSummaryResponse>> createOrder(
        @RequestBody CreateOrderRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String userId)
```

### Frontend: VeeValidate + Yup Integration

VeeValidate 4.x + Yup are project dependencies per architecture spec. Verify in `frontend/customer-website/package.json` before implementing.

**Setup pattern per component:**
```typescript
import { useForm, useField } from 'vee-validate'
import * as yup from 'yup'

const schema = yup.object({ fullName: yup.string().required().min(2) })
const { handleSubmit } = useForm({ validationSchema: schema })
const { value: fullName, errorMessage: fullNameError } = useField<string>('fullName')
```

**PrimeVue template pattern** (PrimeVue 4.x does NOT auto-integrate with VeeValidate):
```vue
<InputText v-model="fullName" :class="{ 'p-invalid': fullNameError }" />
<small class="p-error">{{ fullNameError }}</small>
```

### Frontend: PrimeVue Stepper (v4.x API)

```vue
<Stepper :value="currentStep" linear>
  <StepList>
    <Step :value="1">Cart Review</Step>
    <Step :value="2">Shipping</Step>
    <Step :value="3">Payment</Step>
    <Step :value="4">Confirm</Step>
  </StepList>
  <StepPanels>
    <StepPanel :value="1"><StepCartReview /></StepPanel>
    <StepPanel :value="2"><StepShippingAddress /></StepPanel>
    <StepPanel :value="3"><StepPayment /></StepPanel>
    <StepPanel :value="4"><StepConfirm /></StepPanel>
  </StepPanels>
</Stepper>
```

Imports: `import Stepper from 'primevue/stepper'`, `import StepList from 'primevue/steplist'`, `import Step from 'primevue/step'`, `import StepPanels from 'primevue/steppanels'`, `import StepPanel from 'primevue/steppanel'`

### Frontend: Checkout Store — Saga Message Cycling

```typescript
const SAGA_MESSAGES = ['Creating your order...', 'Reserving items...', 'Processing payment...']
let msgIndex = 0
let intervalId: ReturnType<typeof setInterval> | null = null

async function placeOrder(request: PlaceOrderRequest) {
  isPlacingOrder.value = true
  sagaMessage.value = SAGA_MESSAGES[0]
  intervalId = setInterval(() => {
    msgIndex = (msgIndex + 1) % SAGA_MESSAGES.length
    sagaMessage.value = SAGA_MESSAGES[msgIndex]
  }, 1500)
  try {
    const result = await orderApi.placeOrder(request)
    router.push(`/order-confirmation/${result.data.id}`)
  } catch (err: unknown) {
    const code = (err as AxiosError<ApiErrorResponse>).response?.data?.error?.code
    if (code === 'ORDER_PAYMENT_FAILED') {
      error.value = { type: 'PAYMENT_FAILED', message: "Payment couldn't be processed..." }
    } else if (code === 'ORDER_INVENTORY_FAILED') {
      error.value = { type: 'INVENTORY_FAILED', message: 'An item just sold out.' }
    } else {
      error.value = { type: 'UNKNOWN', message: 'Something went wrong. Please try again.' }
    }
  } finally {
    if (intervalId) clearInterval(intervalId)
    isPlacingOrder.value = false
  }
}
```

### Frontend: CartSummary.vue — Current State

The "Proceed to Checkout" button is currently **disabled with a tooltip "Coming soon"**. Simply remove the disabled attribute and add the click handler. Import `useAuthStore` and `useRouter` inside the component.

### Frontend: Build Request in StepConfirm

```typescript
import { useCartStore } from '@/stores/useCartStore'
import { useCheckoutStore } from '@/stores/useCheckoutStore'

function buildRequest(): PlaceOrderRequest {
  const cart = useCartStore()
  const checkout = useCheckoutStore()
  return {
    items: cart.items.map(item => ({
      productId: String(item.productId),   // Backend expects String
      productName: item.productName,
      quantity: item.quantity,
      unitPrice: item.price,
    })),
    shippingAddress: formatAddress(checkout.shippingData!),
  }
}
```

### Project Structure Notes

- Backend built from monorepo root: `mvn -pl order-service -am clean test` (NOT from service directory — proto deps require parent reactor)
- Frontend workspace: `npm run test:unit` from `frontend/customer-website/`
- Pre-existing test failure: `OrderServiceCancelTest` has 7 failing tests — confirmed pre-existing in Story 4.6, do NOT attempt to fix

### Previous Story Intelligence (Story 4.6)

- `X-User-Id` header: **always** `@RequestHeader(value = "X-User-Id", required = false)` + guard null/blank → `throw new ResourceNotFoundException(...)` for 404
- `ApiResponse<T>` for single item responses; `traceId` from `MDC.get("traceId")`
- `apiClient` (`src/api/client.ts`) auto-injects `Authorization: Bearer {token}` and `X-User-Id` — do NOT manually set these in store or API layer
- Pinia setup stores: use two separate try/catch blocks for independent async operations (don't nest) to avoid corrupting `isLoading`/`error` state
- Stale state bug pattern: clear current view state at the start of fetch actions (e.g., `currentOrder.value = null` before fetch to prevent flash of old data)
- Router guard: `meta: { requiresAuth: true }` + existing `router.beforeEach` redirects to `/` — reuse this pattern for `/checkout` and `/order-confirmation/:orderId`

### References

- Epic 4 Story 4.7 full AC: `_bmad-output/planning-artifacts/epics.md` lines 1060–1108
- UX spec checkout flow: `_bmad-output/planning-artifacts/ux-design-specification.md` — UX-DR11, UX-DR15, UX-DR16
- Existing `OrderService.createOrder`: `backend/order-service/src/main/java/com/robomart/order/service/OrderService.java`
- Existing `OrderRestController`: `backend/order-service/src/main/java/com/robomart/order/web/OrderRestController.java`
- CartSummary to enable: `frontend/customer-website/src/components/cart/CartSummary.vue`
- CartView (add toast): `frontend/customer-website/src/views/CartView.vue`
- Existing orderApi: `frontend/customer-website/src/api/orderApi.ts`
- Existing order types: `frontend/customer-website/src/types/order.ts`
- Previous story (patterns + learnings): `_bmad-output/implementation-artifacts/4-6-implement-customer-order-tracking-ui.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

N/A

### Completion Notes List

- Backend `OrderService.createOrder()` returns Order with CANCELLED status when saga fails (does not throw). Controller checks `order.getStatus() == CANCELLED` and throws `OrderPaymentFailedException` or `OrderInventoryFailedException` based on `cancellationReason` text.
- Added `ORDER_PAYMENT_FAILED` and `ORDER_INVENTORY_FAILED` to `ErrorCode` enum in `common-lib`.
- `useAuthStore.login()` extended with optional `redirectTo?` 3rd param so CartSummary checkout button can redirect back to `/checkout` (not `/cart`) after Keycloak login.
- VeeValidate 4.14.7 + Yup 1.6.1 installed as new deps in `frontend/customer-website/package.json`.
- `CartSummary.spec.ts` updated: added Pinia setup + `useAuthStore` mock (5 tests were failing due to missing Pinia in existing tests).
- All 216 frontend unit tests passing after fixes.

### File List

**Backend:**
- `backend/common-lib/src/main/java/com/robomart/common/logging/ErrorCode.java` — added ORDER_PAYMENT_FAILED, ORDER_INVENTORY_FAILED
- `backend/order-service/src/main/java/com/robomart/order/exception/OrderPaymentFailedException.java` (new)
- `backend/order-service/src/main/java/com/robomart/order/exception/OrderInventoryFailedException.java` (new)
- `backend/order-service/src/main/java/com/robomart/order/web/CreateOrderItemRequest.java` (new)
- `backend/order-service/src/main/java/com/robomart/order/web/CreateOrderRequest.java` (new)
- `backend/order-service/src/main/java/com/robomart/order/web/OrderRestController.java` — added POST /api/v1/orders
- `backend/order-service/src/test/java/com/robomart/order/unit/web/OrderRestControllerTest.java` — added 4 createOrder tests
- `backend/order-service/src/test/java/com/robomart/order/unit/service/OrderServiceCreateTest.java` (new)

**Frontend:**
- `frontend/customer-website/package.json` — added vee-validate, yup
- `frontend/customer-website/src/types/order.ts` — added CreateOrderItemPayload, PlaceOrderRequest
- `frontend/customer-website/src/types/checkout.ts` (new)
- `frontend/customer-website/src/api/orderApi.ts` — added placeOrder()
- `frontend/customer-website/src/stores/useAuthStore.ts` — extended login() with redirectTo param
- `frontend/customer-website/src/stores/useCheckoutStore.ts` (new)
- `frontend/customer-website/src/components/checkout/CheckoutOrderSummary.vue` (new)
- `frontend/customer-website/src/components/checkout/StepCartReview.vue` (new)
- `frontend/customer-website/src/components/checkout/StepShippingAddress.vue` (new)
- `frontend/customer-website/src/components/checkout/StepPayment.vue` (new)
- `frontend/customer-website/src/components/checkout/StepConfirm.vue` (new)
- `frontend/customer-website/src/components/checkout/SagaProgressOverlay.vue` (new)
- `frontend/customer-website/src/views/CheckoutView.vue` (new)
- `frontend/customer-website/src/views/OrderConfirmationView.vue` (new)
- `frontend/customer-website/src/router/index.ts` — added /checkout, /order-confirmation/:orderId routes
- `frontend/customer-website/src/components/cart/CartSummary.vue` — enabled checkout button
- `frontend/customer-website/src/views/CartView.vue` — added out_of_stock toast
- `frontend/customer-website/src/stores/__tests__/useCheckoutStore.spec.ts` (new)
- `frontend/customer-website/src/api/__tests__/orderApi.spec.ts` — added placeOrder test
- `frontend/customer-website/src/components/cart/__tests__/CartSummary.spec.ts` — fixed Pinia/auth mock
