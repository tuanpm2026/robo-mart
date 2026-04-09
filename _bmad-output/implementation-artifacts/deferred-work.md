# Deferred Work

## Deferred from: code review of 1-1-scaffold-monorepo-minimal-development-infrastructure (2026-03-27)

- **common-lib heavyweight dependencies**: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-actuator are compile-scope in common-lib, forcing all consumers to drag in full web server + JPA + actuator. Consider splitting DTOs/exceptions into a lighter module when more services are added.
- **BaseEntity missing @Version**: No optimistic locking field. Add `@Version private Long version` when persistence logic lands in Story 1.2 to prevent lost updates in concurrent scenarios.

## Deferred from: code review of 1-3-implement-elasticsearch-integration-product-sync-via-outbox-pattern (2026-03-28)

- **No distributed lock on outbox polling**: Single-instance deployment for now. Multi-instance requires ShedLock or SELECT FOR UPDATE SKIP LOCKED to prevent duplicate event publishing.
- **Consumer idempotency via eventId not implemented**: Task 7.5 specifies eventId-based dedup, but ES save by product ID is inherently idempotent (same ID overwrites). EventId dedup needed only if side effects beyond indexing are added.
- **Auto-register schemas should be disabled in production**: `auto.register.schemas=true` is convenient for dev but should be `false` in production to prevent accidental schema mutations.
- **Corrupt events retry forever without retry count limit**: Unpublished events that fail to serialize/publish are retried every second indefinitely. Needs a `retry_count` column or max-retry mechanism to skip permanently broken events.

## Deferred from: code review of 1-4-implement-product-search-with-full-text-filtering (2026-03-28)

- **GlobalExceptionHandler missing HandlerMethodValidationException handler**: Validation errors from @ModelAttribute @Valid may return 500 instead of 400 in Spring Framework 7. Need to add handler for HandlerMethodValidationException.
- **No error handling when Elasticsearch is unavailable**: ProductSearchService.search() does not catch ES connection errors. Should wrap in try-catch and throw ExternalServiceException (503) or implement circuit breaker (Epic 8 scope).
- **No sort for match_all queries**: When no keyword is provided, match_all returns results in non-deterministic order, which can cause inconsistent pagination.
- **No relevance ranking test**: AC #1 specifies relevance ranking with name boost x3 > brand boost x2, but no test verifies ordering.
- **No performance test for 500ms p95 SLA**: AC #6 requires search p95 < 500ms but no load testing artifact exists.
- **Sort parameters from Pageable not validated**: Sort params from query string are passed directly to ES without whitelisting. Invalid sort fields could cause ES errors.

## Deferred from: code review of 1-5-implement-graphql-product-endpoint (2026-03-28)

- **ProductConnection.totalElements uses int cast from long**: `(int) searchResult.pagination().totalElements()` could overflow for >2.1B products. Use Long in ProductConnection DTO for correctness. Low risk at current scale.
- **Stale ES data causes totalElements mismatch**: Products deleted between Elasticsearch search and PostgreSQL fetch are silently dropped from results, causing totalElements to be higher than actual content count. Same eventual consistency gap as REST search endpoint.
- **Schema field name `imageUrl` vs AC example `url`**: GraphQL schema uses `imageUrl` (matching entity field), but AC example shows `url`. The AC is illustrative; `imageUrl` is the correct naming.
- **No explicit GraphQL error handling**: No custom DataFetcherExceptionResolver or @GraphQLExceptionHandler. Spring for GraphQL's default handler wraps exceptions into GraphQL error format. Explicit handling deferred to Epic 8 (resilience).

## Deferred from: code review of 1-6-setup-customer-website-foundation-design-system (2026-03-28)

- **Focus management on route navigation**: After route change, focus should move to `#main-content` for screen reader / keyboard-only users. Add `router.afterEach` hook. Deferred — future story or Epic 8 accessibility hardening scope.
- **Router error boundary / onError handler**: No error handling for component import failures or navigation errors. Add `router.onError()` and async component error boundaries. Deferred — Epic 8 resilience scope.

## Deferred from: code review of 1-7-implement-customer-product-browsing-search-ui (2026-03-28)

- **Search results uses plain div grid instead of PrimeVue DataView**: AC #4 mentions DataView but implementation uses CSS grid — functionally equivalent. Consider using DataView for built-in list/grid toggle if needed later.
- **No request cancellation on rapid navigation**: Category clicks and product navigation don't cancel in-flight API requests. Add AbortController to API client or use watchEffect with cleanup for request cancellation.
- **Hardcoded price slider max ($1000)**: FilterSidebar price range is capped at $1000. Should derive max from actual product data or make configurable.

## Deferred from: code review of 2-1-add-redis-to-infrastructure-implement-cart-service-core (2026-03-29)

- **Race condition on read-modify-write**: CartService.addItem() reads cart, modifies in-memory, saves — no distributed locking. Concurrent adds to same cart could lose items. Needs ShedLock or Redis WATCH/MULTI or Redisson distributed lock (Epic 8 scope).
- **No Redis authentication**: Docker Compose Redis container has no password set. Acceptable for local dev, but production deployment must configure `requirepass` and `spring.data.redis.password`.
- **Missing Redis connection timeouts**: `application.yml` has no explicit `spring.data.redis.timeout` or `spring.data.redis.lettuce.pool` config. Add connection/command timeouts for production readiness.
- **No Redis error handling / circuit breaker**: CartService does not catch `RedisConnectionFailureException`. Service fails with 500 when Redis is down. Wrap in circuit breaker pattern (Epic 8 scope).

## Deferred from: code review of 2-2-implement-cart-persistence-ttl-expiry (2026-03-29)

- **GET cart resets TTL via save() — race condition amplified**: `getCart()` now reads then saves to refresh TTL, creating another read-modify-write race condition. Same underlying issue as Story 2.1 deferred item. Needs distributed locking (Epic 8 scope).
- **No userId/cartId input validation for special characters**: `X-User-Id` and `X-Cart-Id` headers are used directly as Redis keys without sanitization. Malicious characters (newlines, control chars) could be injected. Will be handled by API Gateway input validation in Epic 3.
- **No max length check on userId/cartId**: Extremely long header values could cause Redis memory issues. Will be enforced by API Gateway in Epic 3.
- **Slow TTL integration tests (~150s)**: `shouldExpireCartAfterTtl` and `shouldResetTtlOnCartAccess` use Thread.sleep() for real TTL expiry verification. Consider adding `@Tag("slow")` and running only in nightly CI.

## Deferred from: code review of 2-3-implement-redis-caching-with-event-driven-invalidation (2026-03-29)

- **`findAll()` full cart scan on every product update**: `ProductEventConsumer.onProductUpdated()` calls `cartRepository.findAll()` which scans all carts in Redis. Accepted as MVP tech debt (low cart count). For scale, add a reverse index productId → cartIds (Epic 8 scope).
- **Cart read-modify-write race condition during event processing**: `ProductEventConsumer` reads cart, modifies price, saves — no distributed locking. Concurrent user cart modifications could be lost. Same root cause as Story 2.1 deferred item (Epic 8 scope).
- **No Dead Letter Topic (DLT) for failed Kafka messages**: `KafkaConsumerConfig` uses `DefaultErrorHandler` with `FixedBackOff(1000L, 3)` — after retries, failed messages are silently dropped. Need `DeadLetterPublishingRecoverer` for production (infrastructure concern).
- **No exception handling around individual `cartRepository.save()`**: If save fails mid-loop, partial updates occur and retry processes already-saved carts again. Retry via error handler is adequate for MVP.
- **No atomicity guarantee on multi-cart update**: Each cart saved individually without transaction. Redis doesn't support multi-key transactions. Partial updates possible on crash.
- **Race condition between cache population and invalidation**: Classic cache-aside race — thread reads stale data from DB, preempted, another thread evicts cache, first thread stores stale data. Bounded by 5-minute TTL.
- **Cart TTL not explicitly reset after Kafka price update**: `ProductEventConsumer` saves cart after price update but doesn't explicitly set `updatedAt` or manage TTL. Minor behavioral concern.
- **`AUTO_OFFSET_RESET=earliest` replays historical events on first consumer group creation**: New cart-service consumer group will replay all historical product events on first deploy, each triggering a `findAll()` scan. One-time first-deploy concern.

## Deferred from: code review of 2-4-implement-customer-cart-ui (2026-03-29)

- **Missing product image thumbnails in cart (AC3)**: Backend CartItem DTO only returns `productId, productName, price, quantity, subtotal` — no `imageUrl` field. CartItem.vue uses SVG placeholder. Backend needs to add `imageUrl` to CartItem DTO (or CartService should store imageUrl when adding items). Deferred — backend API change needed.

## Deferred from: code review of 3-3-implement-api-gateway-jwt-validation-rbac (2026-03-30)

- **No rate limiting on cart endpoints**: `/api/v1/cart/**` is `permitAll()` with client-supplied anonymous identity. Attacker can create unlimited anonymous cart sessions. Pre-existing from Epic 2 design. Consider rate limiting in Epic 8 (resilience).
- **GraphQL endpoint may bypass path-based RBAC**: `/graphql` is public. If federated mutations for orders/admin are added later, path-based security cannot distinguish operations. Architectural concern for future GraphQL federation/stitching.

## Deferred from: code review of 3-4-implement-anonymous-cart-merge-on-login (2026-03-30)

- **Race condition: non-atomic read-modify-write in CartMergeService**: No Redis transaction or distributed lock around findById → addItem → save → deleteById. Concurrent merge calls could duplicate items. Acknowledged in spec as acceptable risk for one-time-per-session operation. Same root cause as Story 2.1 deferred item (Epic 8 scope).
- **X-User-Id trusted without ownership validation on anonymousCartId**: Cart service trusts X-User-Id header and accepts any anonymousCartId in request body. An authenticated user could theoretically merge another user's cart by guessing their UUID. Mitigated by UUID randomness (128-bit) and gateway auth enforcement. Pre-existing trust model.
- **No upper bound on cart item count during merge**: Cart.addItem() caps quantity at 9999 per item but does not limit total distinct items. Anonymous cart with many unique products could bloat the auth cart. Pre-existing — addToCart() has same issue.
- **Missing MissingRequestHeaderException handler returns 500 instead of 400**: When X-User-Id header is missing, Spring throws MissingRequestHeaderException which falls through to generic Exception handler returning 500. Pre-existing — affects all endpoints using @RequestHeader.

## Deferred from: code review of 4-1-add-order-infrastructure-services-databases-grpc (2026-03-30)

- **InventoryItem.productId (Long) vs proto/Avro product_id (String) type mismatch**: Entity uses Long to match DB BIGINT, proto/Avro uses String. Mapping layer (String.valueOf/Long.parseLong) to be added when gRPC service implementation lands in later stories. Same applies to OrderItem.productId.
- **Outbox index uses single-column (published) instead of composite (published, created_at)**: The story template recommends `(published, created_at)` for sorted polling, but implementation follows existing product-service pattern with `(published)` only. Optimize when outbox volume warrants it.
- **SagaAuditLog.orderId VARCHAR vs orders.id BIGINT**: By design for cross-service string references per proto convention. No FK constraint since saga audit log uses string IDs for flexibility.
- **Proto timestamps use int64 instead of google.protobuf.Timestamp**: Acceptable for infrastructure story. Can be updated in future proto evolution when needed.
- **SQL tables missing CHECK constraints for quantities and amounts**: Consistent with existing product-service pattern. Add `CHECK (quantity > 0)`, `CHECK (amount > 0)`, `CHECK (available_quantity >= 0)` etc. when business logic is implemented.

## Deferred from: code review of 4-3-implement-payment-service-with-idempotency-retry (2026-03-31)

- **Clock skew on idempotency TTL check**: `Instant.now()` used for TTL comparison susceptible to clock skew across replicas. Use database `CURRENT_TIMESTAMP` in a custom repository query for production multi-instance deployment. Architectural decision for production deployment.

## Deferred from: code review of 4-4-implement-order-saga-orchestrator-phase-a-core-flow (2026-03-31)

- **Multiple service instances recovering same stale orders**: recoverStaleSagas() has no distributed lock. Concurrent startup of multiple replicas will all attempt to compensate the same stale orders, potentially double-releasing inventory. Requires Redis distributed lock or DB advisory lock (ShedLock). Out of Phase A scope.
- **recoverStaleSagas() blocks startup synchronously for large volumes**: Sequential blocking gRPC calls per stale order. With thousands of stale orders this blocks readiness indefinitely and risks K8s liveness probe failure. Future: async recovery with bounded concurrency and a startup timeout budget.
- **No gRPC deadlines/timeouts configured**: Blocking stubs have no deadline set. Slow/hung downstream services will block saga threads indefinitely. Configure per-call deadlines via stub.withDeadlineAfter() or global channel config in production.
- **Hardcoded currency "USD" in ProcessPaymentStep**: Multi-currency support not in scope for Phase A. Revisit when multi-region/multi-currency requirements emerge.
- **Order.setVersion() public accessor exposes version manipulation**: Needed for the saveAndFlush version-sync pattern. Low risk in current codebase but violates encapsulation. Future: make package-private or adopt a different version-sync approach.
- **Orphaned payment in PAYMENT_PROCESSING crash recovery (Phase A known limitation)**: recoverStaleSagas() cancels order without checking if payment already succeeded. No refund is issued. Deferred to Story 4.5 which implements full cancel-order saga with payment compensation.

## Deferred from: code review of 4-5-implement-order-cancellation-with-saga-compensation (2026-04-01)

- **sagaId used as both sagaId and correlationId in logSagaStep**: `logSagaStep(sagaId, sagaId, ...)` passes orderId for both args. Pre-existing pattern established in Story 4.4; not introduced by this story. Fix when saga audit log reporting needs separate correlation IDs.
- **SagaStepException retryable=false for all gRPC errors in RefundPaymentStep**: Transient errors like UNAVAILABLE are marked non-retryable, meaning a temporarily unavailable payment service skips refund permanently. Intentional best-effort design for Story 4.5 scope; revisit in Epic 8 resilience work.
- **Best-effort cancellation has no persistent refund_failed flag**: When refund fails during CONFIRMED order cancellation, error is logged but no flag/field persists on the order. Reconciliation job cannot identify orders with failed refunds. Deferred — reconciliation tooling is out of Story 4.5 scope (Epic 6 or Epic 8).
- **Empty items list when reservationId non-null**: If orderItemRepository.findByOrderId() returns empty for an order with a reservationId, ReleaseInventoryStep sends a release request with no items. Items are loaded upfront in cancelOrder() so this shouldn't occur in normal operation; add guard when defensive hardening lands in Epic 8.
- **UnsupportedOperationException in RefundPaymentStep.compensate()**: Throws UnsupportedOperationException if compensate() is ever called. Intentional defensive design (forward-only step). Consider logging + no-op if this creates issues during future refactoring.

## Deferred from: code review of 4-6-implement-customer-order-tracking-ui (2026-04-03)

- **`PagedResponse`/`ApiResponse`/`PaginationMeta` imported from `@/types/product` in order API/store**: Cross-domain coupling. Should be extracted to a shared types module. Pre-existing pattern in the codebase.
- **Router guard calls `useAuthStore()` outside component setup**: Pinia timing risk if bootstrap order changes (e.g., `app.use(pinia)` moved after router init). Pre-existing pattern used by other routes.
- **`orderId` parsed via `Number(route.params.id)`**: Floats (e.g., `3.14`) pass `isNaN` guard but are rejected by backend with 400. Large integers lose precision silently. Backend validation is the effective guard.
- **`getOrderForUser` not `@Transactional`**: Each repository call has its own transaction. Currently safe because `getOrder()` explicitly calls `setItems()`. If that changes, `order.getItems()` could throw `LazyInitializationException`. Add `@Transactional(readOnly=true)` as a safety net.
- **`OrderItem` uses `productId` as `v-for` key in OrderDetailView**: Duplicate products in one order would cause Vue rendering bugs. Fixing requires adding an item `id` field to `OrderItemResponse` DTO (backend change).
- **`aria-valuetext` on progressbar uses step label not actual order status**: For CANCELLED orders at step 0, screen readers announce "Order received" instead of "Cancelled". Minor a11y issue.

## Deferred from: code review of 5-1-setup-admin-dashboard-foundation-design-system (2026-04-06)

- **JWT signature not verified client-side**: Admin auth store decodes JWT payload with atob() without verifying signature. Architectural decision deferred to the admin OAuth story — the whole token management flow will be rewritten with proper OIDC. `useAdminAuthStore.ts`
- **accessToken in public Pinia store API**: Raw token string is included in store's return value, accessible via Vue Devtools. Consider making it private when the auth flow is hardened in the admin OAuth story. `useAdminAuthStore.ts`
- **useBreadcrumb() composable not extracted**: Breadcrumb label computed inline in AdminLayout rather than as a standalone composable. Functionally equivalent for this story's single-level breadcrumb; extract when multi-segment breadcrumbs are needed. `AdminLayout.vue`
- **VueUse useEventListener not used**: CommandPalette uses raw addEventListener/removeEventListener. Cleanup is correct; migrate to VueUse pattern when VueUse is adopted more broadly in admin-dashboard. `CommandPalette.vue`

## Deferred from: code review of 4-7-implement-customer-checkout-flow-ui (2026-04-03)

- **Postal code regex is US-only** (`/^\d{5}(-\d{4})?$/`): International postal codes rejected. Design choice for MVP single-market; revisit when multi-region support is needed. `StepShippingAddress.vue`
- **Auth guard redirects to `/` for expired-token re-entry to `/checkout`**: User loses checkout context. Pre-existing router guard behavior not introduced by this story; revisit when deep-link auth recovery is needed.

## Deferred from: code review of 5-4-implement-admin-inventory-management (2026-04-07)

- **Frontend `listProducts(0, 1000)` scalability ceiling**: `useInventoryStore` loads all products in a single call for client-side enrichment. Redesign needed (backend join or search endpoint) if product catalog exceeds 1000 items.
- **Inventory service has no `SecurityFilterChain`**: Defense-in-depth — service relies solely on API Gateway RBAC. Add service-level security when system-wide security hardening lands (Epic 8 scope).
- **Store missing "filters" state (AC6)**: `useInventoryStore` does not expose filter state. AC6 wording is ambiguous; filter UI is an enhancement. Add when filtering requirements are clarified.
- **Concurrent restock + reserve integration test**: Testing OptimisticLockException in integration requires concurrent thread setup. Deferred to Epic 8 resilience testing.

## Deferred from: code review of 5-3-implement-product-image-upload (2026-04-07)

- **Stale FileUpload queue entries after upload clears**: PrimeVue `FileUpload` component keeps internal file entries visible after a successful upload completes. No public API to reset internal state from outside. Framework limitation — revisit if PrimeVue 5.x exposes a `clear()` ref method.

## Deferred from: code review of 5-2-implement-admin-product-crud (2026-04-07)

- **SKU uniqueness TOCTOU race**: `existsBySku()` check not atomic with `save()`. Requires a unique DB constraint on `sku` column + exception handler for `DataIntegrityViolationException`. Revisit in a future migration story.
- **Unbounded `getAllCategories()` query**: `categoryRepository.findAll()` has no pagination. Acceptable while categories remain a small set (~10-50). Add `@Cacheable` or pagination if catalog grows.
- **401 redirect fires error toast before navigation completes**: `adminClient.ts` response interceptor sets `window.location.href` then rejects promise; callers show error toast briefly before redirect. Pre-existing auth pattern from Story 5.1; revisit when auth middleware is unified.
- **Delete→Update concurrent race condition**: Two admins editing/deleting the same product concurrently leads to last-write-wins. `@Version` field exists on `Product` but is not enforced at service level. Revisit when admin team grows beyond single user.
- **Bulk delete selection doesn't span pagination pages**: PrimeVue DataTable `v-model:selection` only tracks current page. Deleting selected items across pages silently skips off-page items. Low impact given 100-row default fetch; revisit if pagination is reduced.

## Deferred from: code review of 5-4-implement-admin-inventory-management (2026-04-07)

- **Frontend `listProducts(0, 1000)` scalability ceiling**: `useInventoryStore.loadInventory()` fetches up to 1000 products for client-side enrichment. If catalog exceeds 1000, products beyond page 1 get fallback names. Consider a server-side join or a dedicated `productId → name` endpoint.
- **Inventory service has no `SecurityFilterChain`**: Admin endpoints rely solely on API Gateway for RBAC. Direct access to inventory-service bypasses auth. Add `@PreAuthorize` or a minimal `SecurityFilterChain` for defense-in-depth when system-wide security hardening is addressed.
- **Store missing "filters" state (AC6)**: `useInventoryStore` has no filter state for searching by product name or filtering by low-stock status. AC6 mentions "filters" but spec wording is ambiguous. Add when UX feedback indicates need.

## Deferred from: code review of 5-5-implement-admin-order-management (2026-04-08)

- **No `@PreAuthorize` defense-in-depth on admin controller**: `OrderAdminRestController` relies solely on API Gateway RBAC. Direct access bypasses auth. Same pattern as other admin controllers. Add when system-wide security hardening lands (Epic 8 scope).
- **Integration tests don't clean up between tests — order pollution**: `OrderAdminRestIT` inserts rows but doesn't truncate between tests. Pre-existing pattern in order-service integration tests (same as OrderCancellationIT, OrderSagaIT).

## Deferred from: code review of 6-2-implement-low-stock-alerts-cart-expiry-notifications (2026-04-09)

- **TTL value stale in CartExpiryWarningEvent**: The `expiresInSeconds` value captured during Redis SCAN may be slightly lower than the actual TTL by the time the event is built and delivered. The email will show an approximation. Acceptable timing approximation for UX purposes; document as "approximately N hours remaining".

## Deferred from: code review of 6-1-implement-notification-service-core-order-notifications (2026-04-08)

- **No dead-letter topic after DefaultErrorHandler retry exhaustion**: `KafkaConsumerConfig` uses `DefaultErrorHandler(FixedBackOff(1000L, 3))` with no `DeadLetterPublishingRecoverer`. After 3 retries, failed notification events are permanently lost. Explicitly deferred to Story 6.3 (DLQ implementation).
- **userId used as email address without user lookup**: `NotificationService.sendAndLog()` passes `userId` (Keycloak UUID) directly as the email `to` address. MockEmailService logs it silently, but a real SMTP implementation would fail. Requires a user-profile service or Keycloak admin API lookup to resolve userId → email. Out of 6.1 scope.
- **PENDING notification status for reliable before-send logging**: Log record is only created after the email attempt. If the process crashes between email send and DB save, email is sent with no audit record. A `PENDING` status written before sending, updated to `SENT`/`FAILED` after, would prevent this. Enhancement beyond 6.1 scope.
- **Null Avro event fields (.toString() NPE risk)**: `OrderEventConsumer` calls `.toString()` on `getNewStatus()`, `getPreviousStatus()`, `getOrderId()` without null checks. Avro schema enforces non-null for these required fields in current schema version; revisit if schema evolves to make these fields optional.
- **Unbounded repository queries without Pageable**: `NotificationLogRepository.findByRecipient()` and `findByNotificationType()` return `List<>` with no pagination. Pre-existing pattern; add `Pageable` when notification volume warrants it.
- **trace_id nullable in notification_log**: `trace_id VARCHAR(64)` has no NOT NULL constraint. Legitimate null when no active Micrometer span (e.g., during startup or test runs without tracing configured).

## Deferred from: code review of 6-3-implement-dead-letter-queue-for-failed-events (2026-04-09)

- **No metrics/alerting in DlqConsumer**: DlqConsumer only logs at ERROR level. No Micrometer counter or admin notification. DLQ messages could go unnoticed if log monitoring is not configured. Out of scope for Story 6.3.
- **No `@DirtiesContext` or test isolation for DLQ state across integration tests**: Shared Spring context among NotificationIntegrationIT, NotificationAlertIT, DlqRoutingIT. DLQ messages from earlier tests could leak. Pre-existing pattern across all notification-service integration tests.
- **`TestKafkaProducerConfig` bean name `producerFactory` could conflict with `dlqProducerFactory`**: Multiple `ProducerFactory` and `KafkaTemplate` beans without `@Primary`. Works with current `@Qualifier` usage. Pre-existing fragility.
