---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
  - step-03-create-stories
  - step-04-final-validation
status: complete
inputDocuments:
  - prd.md
  - architecture.md
  - ux-design-specification.md
---

# robo-mart - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for robo-mart, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

FR1: Customer can browse products by category
FR2: Customer can search products by keyword with full-text search
FR3: Customer can filter search results by price, brand, rating, and category
FR4: Customer can view product detail including description, specifications, price, images, and stock availability
FR5: System can sync product data from PostgreSQL to Elasticsearch within 30 seconds of data change (aligned with NFR32 eventual consistency window)
FR6: Customer can query products via GraphQL endpoint with flexible filtering and nested data
FR7: Customer can add products to cart
FR8: Customer can update product quantity in cart
FR9: Customer can remove products from cart
FR10: Customer can view cart summary with total price
FR11: System can persist cart data across browser sessions for authenticated users
FR12: System can expire cart after configurable TTL (default 24 hours) and release held references
FR13: System can send email notification to customer 2 hours before cart expiration with cart contents summary and direct checkout link
FR14: Customer can place an order from cart contents
FR15: System can coordinate order creation across services using Saga pattern (Order → Inventory → Payment)
FR16: System can execute compensating transactions when any step in the order flow fails
FR17: Customer can view order history and order details
FR18: Customer can track order status changes (PENDING → CONFIRMED → SHIPPED → DELIVERED)
FR19: System can manage order state transitions via state machine
FR20: Customer can cancel an order in PENDING or CONFIRMED status
FR21: System can execute Saga compensation on order cancellation (refund payment → release inventory → send cancellation notification)
FR22: System can resolve race condition between order cancellation and in-flight payment — if payment completes before cancellation is processed, system issues automatic refund; if cancellation is processed first, payment request is rejected
FR23: System can reserve inventory atomically under concurrent requests without overselling
FR24: Admin can view current stock levels for all products
FR25: Admin can update stock quantities (restock)
FR26: System can generate low-stock alerts when inventory falls below configurable threshold (default: 10 units per product)
FR27: System can release reserved inventory when order is cancelled or payment fails
FR28: System can process payments via mock payment provider
FR29: System can guarantee payment idempotency (no duplicate charges)
FR30: System can retry failed payments with exponential backoff (max 3 retries, initial delay 1 second, backoff multiplier 2x)
FR31: System can refund payments automatically when order compensation is triggered
FR32: System can handle payment service unavailability gracefully (order remains in PAYMENT_PENDING)
FR33: System can send order confirmation notification to customer after successful purchase
FR34: System can send payment success/failure notification to customer
FR35: System can send low-stock alerts to admin
FR36: System can deliver notifications via event-driven consumption from Kafka
FR37: Customer can register and log in via Keycloak (email/password)
FR38: Customer can register and log in via social login (Google, GitHub)
FR39: Admin can log in via Keycloak with admin role
FR40: System can enforce role-based access control (Customer vs Admin permissions)
FR41: System can validate JWT tokens at API Gateway for all protected endpoints
FR42: System can manage token refresh without requiring re-login
FR43: Admin can create, read, update, and delete products
FR44: Admin can view and manage all orders with filtering by status
FR45: Admin can view order details including items, customer info, payment status
FR46: Admin can view real-time order events via WebSocket
FR47: Admin can view real-time inventory alerts via WebSocket
FR48: Admin can view system health status via WebSocket — service up/down state, CPU/memory usage, Kafka consumer lag, database connection pool utilization
FR49: Admin can view reports filtered by time range (today, last 7 days, last 30 days, custom date range): top selling products (by quantity and revenue), revenue by category, order trends (count and status distribution) — powered by CQRS read models with data sync within 30 seconds
FR50: System can route all external requests through API Gateway
FR51: System can communicate between services via gRPC for synchronous calls
FR52: System can communicate between services via Kafka for asynchronous events
FR53: System can detect service failures and open circuit breaker to prevent cascading failures
FR54: System can queue failed events in Dead Letter Queue for later reprocessing
FR55: System can guarantee event delivery from database to Kafka via Outbox Pattern
FR56: System can degrade gracefully when dependent services are unavailable — Payment Service down: orders held in PAYMENT_PENDING; Inventory Service down: order placement blocked with retry; Notification Service down: events queued in DLQ, orders proceed normally; Elasticsearch down: product search falls back to PostgreSQL query
FR57: System can execute graceful shutdown on all services: stop accepting new requests, complete in-flight requests, commit Kafka consumer offsets, and close database connections before termination
FR58: System can trace requests across all services with distributed tracing (REST, gRPC, Kafka)
FR59: System can propagate correlation ID through entire request chain
FR60: System can expose health check endpoints (liveness and readiness) for every service
FR61: System can apply rate limiting at API Gateway — default 100 requests/minute per authenticated user, 20 requests/minute for unauthenticated clients, configurable per endpoint
FR62: System can manage centralized configuration for all services
FR63: System can execute database schema migrations automatically via Flyway
FR64: System can cache frequently accessed data (product details, search results) in Redis with configurable TTL per data type (default: 5 minutes for product details, 1 minute for search results)
FR65: System can invalidate cached data automatically when source data changes via Kafka event-driven cache invalidation
FR66: System can aggregate data from multiple services into a single API response at the gateway level — e.g., order detail combines Order Service (order data) + Product Service (product info) + Payment Service (payment status) into one response
FR67: System can reconstruct entity state from event history for Order Service
FR68: System can replay events from event store to rebuild CQRS read models on demand — admin triggers rebuild, system reprocesses all events sequentially, read model reflects current state upon completion
FR69: System can discover service instances dynamically without hardcoded addresses
FR70: Admin can view Dead Letter Queue messages and trigger manual reprocessing
FR71: System can detect data inconsistencies between services via scheduled reconciliation jobs (inventory count vs order records, payment records vs order status) and alert admin when variance exceeds threshold (default: >1% discrepancy or >5 unit absolute difference)
FR72: System can maintain audit trail of all state-changing operations across services with fields defined in NFR18 (actor, action, timestamp, trace ID)
FR73: System can paginate and sort results for all list-based queries (products, orders, search results)
FR74: Admin can upload and manage product images — supported formats: JPEG, PNG, WebP; max file size: 5MB; stored in local filesystem or object storage; up to 10 images per product

### NonFunctional Requirements

NFR1: API response time < 200ms for 95th percentile under normal load (single service call)
NFR2: Product search via Elasticsearch returns results < 500ms for 95th percentile
NFR3: Order placement end-to-end (Saga flow) completes within 3 seconds
NFR4: gRPC inter-service calls complete < 50ms for 95th percentile
NFR5: WebSocket events delivered to Admin Dashboard within 1 second of occurrence
NFR6: System handles 100 concurrent order placements without data corruption or overselling
NFR7: Kafka event processing lag stays under 5 seconds during normal operation
NFR8: System sustains minimum 50 orders per second under peak load
NFR9: Service startup to ready state within 30 seconds (K8s readiness probe passes)
NFR10: Database connection pools (HikariCP) configured per service with max pool size proportional to expected concurrent load — connection acquisition timeout < 5 seconds
NFR11: All external API communication encrypted via HTTPS/TLS
NFR12: All gRPC inter-service communication encrypted via TLS
NFR13: JWT tokens expire within 15 minutes; refresh tokens within 24 hours
NFR14: All sensitive configuration (DB passwords, API keys) stored in K8s Secrets, never in code
NFR15: All user passwords managed by Keycloak with industry-standard hashing (bcrypt/argon2)
NFR16: API Gateway rejects requests without valid JWT for all protected endpoints
NFR17: RBAC enforced at API Gateway level — customers cannot access admin endpoints
NFR18: All state-changing operations logged in audit trail with actor, action, timestamp, and trace ID
NFR19: Each microservice independently scalable via K8s horizontal pod autoscaling
NFR20: Kafka consumers support partition-based parallel processing for horizontal scaling
NFR21: System maintains p95 API latency within 2x of normal-load baselines (NFR1-NFR4) under 10x load increase with proportional horizontal scaling
NFR22: Database per service pattern ensures independent scaling — each service's database query latency < 100ms at 95th percentile under peak load with no cross-service database dependencies
NFR23: Redis cluster supports horizontal scaling for cart and caching workloads
NFR24: Stateless services — no local state, all session data in Redis/DB
NFR25: Each service operates within K8s resource limits — default request: 256Mi memory / 250m CPU, default limit: 512Mi memory / 500m CPU, adjusted per service based on profiling
NFR26: System recovers from single service failure without manual intervention (Circuit Breaker + DLQ)
NFR27: No data loss during service outages — events persisted via Outbox Pattern before Kafka publish
NFR28: DLQ messages retained for minimum 7 days before expiry
NFR29: K8s liveness probes restart unresponsive services within 30 seconds
NFR30: K8s readiness probes prevent routing traffic to services not yet connected to dependencies
NFR31: Database migrations (Flyway) executed automatically on service startup without downtime
NFR32: System maintains data consistency across services within 30 seconds (eventual consistency window)
NFR33: System continues serving read operations with >95% success rate when any single non-critical service is unavailable — cached data served for product queries, order history remains accessible from Order Service database
NFR34: System recovers to full operation within 60 seconds after failed service restarts
NFR35: Kafka consumers use at-least-once delivery with manual offset commit — combined with idempotency to guarantee no message loss and no duplicate processing
NFR36: Distributed lock TTL set per operation type (default 10 seconds for inventory reservation) — system detects and recovers from expired locks with compensating action
NFR37: All services execute graceful shutdown within K8s terminationGracePeriodSeconds (default 30 seconds) — zero in-flight request loss during pod termination
NFR38: Every request traceable end-to-end via distributed tracing (unique trace ID)
NFR39: Correlation ID present in all log entries, error responses, and Kafka message headers
NFR40: Structured JSON logging across all services with mandatory fields: timestamp (ISO-8601), level, service-name, trace-id, correlation-id, message — enforced via shared logging configuration
NFR41: Health check endpoints respond within 1 second for both liveness and readiness probes
NFR42: All services expose metrics via Micrometer/Prometheus endpoint — request rate, error rate (target < 1% under normal load), p50/p95/p99 latency, active connections, and Kafka consumer lag
NFR43: Every committed transaction is durable across service boundaries — verified by integration tests that kill services mid-transaction and confirm data recovery via Outbox replay with zero missing events
NFR44: Saga compensating transactions execute within 10 seconds of failure detection
NFR45: Idempotency keys prevent duplicate processing for minimum 24 hours
NFR46: All services follow shared project structure template (controller/service/repository layers, config package, exception package) validated by ArchUnit tests in CI pipeline
NFR47: API documentation (OpenAPI/Swagger) auto-generated from code annotations — CI pipeline fails if generated spec differs from committed spec (drift detection)
NFR48: All .proto files versioned with backward-compatible evolution rules — no removing or renaming existing fields, new fields use next available field number, deprecated fields marked with reserved, validated by buf lint in CI
NFR49: CI/CD pipeline executes full build + test + deploy in under 15 minutes
NFR50: All services deployable independently without coordinated releases
NFR51: Zero-downtime deployments via K8s rolling update strategy
NFR52: Test coverage minimum 80% for unit tests per service
NFR53: All integration tests pass with Testcontainers — no dependency on external environments
NFR54: Contract tests validate all service-to-service interfaces on every build
NFR55: CI pipeline blocks deployment if any test (unit, integration, contract) fails
NFR56: Full test suite (unit + integration + contract) completes within 10 minutes per service
NFR57: No flaky tests — any test with >1% failure rate must be fixed or quarantined within 24 hours
NFR58: All API and event schema changes must be backward-compatible — new versions support old consumers for minimum 2 release cycles or 14 days, whichever is longer
NFR59: Failed test reports include full context: request/response payloads, service logs, and trace IDs for reproducibility
NFR60: Test data setup and teardown isolated per test execution — no cross-test pollution, each test starts with known state
NFR61: Chaos testing validates system recovery: random service termination, network latency injection, and resource exhaustion — system returns to healthy state within 60 seconds (per NFR34 recovery target)
NFR62: Contract tests cover all communication protocols independently: Pact for REST APIs, Protobuf schema validation for gRPC, and schema registry validation for Kafka events

### Additional Requirements

- **Starter Template**: Architecture specifies Custom Multi-Module Maven (Backend) + create-vue (Frontend) — impacts Epic 1 Story 1 for project initialization
- Monorepo strategy — all backend services, frontend apps, shared libraries, and infrastructure config in single repository
- 5 shared library modules must be created: common-lib (exceptions, DTOs, logging, base entities), security-lib (JWT filter, security config), proto (gRPC .proto files + stubs), events (Kafka Avro schemas), test-support (Testcontainers configs, test annotations, TestData builders, EventAssertions)
- Docker Compose with profiles: core (PostgreSQL x6, Redis, Kafka, Elasticsearch, Keycloak, Schema Registry — ~6-8GB RAM) and full (core + Pact Broker + Grafana + Prometheus + Loki + Tempo + Alloy — ~12-16GB RAM)
- Avro + Confluent Schema Registry for Kafka event serialization with schema evolution support
- Spring gRPC 1.0.x for inter-service synchronous communication with .proto contracts
- mTLS for gRPC inter-service security with long-lived connections + TLS session resumption
- Phased Saga implementation: Phase A (core Saga with order flow — enum state machine, happy path + compensation) in Phase 2, Phase B (hardened — idempotent steps, timeouts, dead saga detection) in Phase 4
- Resilience4j for circuit breaker, retry, rate limiter, bulkhead, and time limiter
- OpenTelemetry (Spring Boot 4 native) + Grafana Tempo (tracing) + Grafana Loki + Alloy (logs) + Prometheus (metrics) + Grafana (dashboards) for observability stack
- Helm for K8s manifest templating with environment-specific values files
- GitHub Container Registry (ghcr.io) for Docker images
- Pact Broker for REST contract testing, Protobuf validation for gRPC, Schema Registry for Kafka event contracts
- No Config Server — each service uses application.yml per profile + K8s ConfigMaps/Secrets for production
- Seed data via Flyway repeatable migrations (R__seed_data.sql) activated by demo Spring profile — ~50 products, sample orders, pre-configured Keycloak realm with demo users
- Implementation follows vertical slice approach: infrastructure → Product Service + search → Cart Service → API Gateway + Auth → Order/Inventory/Payment Saga → Customer checkout → Admin Dashboard → Notification Service → Admin real-time → Observability → Testing pyramid
- Outbox Pattern: polling-based publisher (1s interval, batch 50, 7-day cleanup) per service with outbox table
- Kafka event envelope standard: eventId, eventType, aggregateId, aggregateType, timestamp, version, payload + headers (x-correlation-id, x-trace-id, x-user-id)
- REST API response wrapper: {data, traceId} for success, {error: {code, message, details}, traceId, timestamp} for errors — GraphQL follows native spec (no custom wrapper)
- Backend exception hierarchy: RoboMartException → ResourceNotFoundException (404), BusinessRuleException (409), ValidationException (400), ExternalServiceException (503)
- MapStruct for all entity ↔ DTO mappings
- AssertJ for all test assertions, TestData builders for test data
- Test naming: should{Expected}When{Condition}()
- ArchUnit for validating backend layer structure in CI
- CI/CD via GitHub Actions: ci-backend.yml, ci-frontend.yml, cd-deploy.yml, schema-compatibility.yml

### UX Design Requirements

UX-DR1: Implement shared design token layer — colors.ts (primary blue palette: primary-50 through primary-900, semantic colors: success/warning/error/info with 50 and 500 variants, neutral grays), typography.ts (Inter font, two scales: Customer 16px base and Admin 14px base), spacing.ts (4px base unit scale: xs/sm/md/lg/xl/2xl/3xl)
UX-DR2: Create two PrimeVue theme presets — customer-theme.js (Aura preset, 16px base font, 1.6 line-height, 24px card padding, 8px border-radius, primary-600 CTA, white background, 200ms animation) and admin-theme.js (Aura preset, 14px base font, 1.4 line-height, 16px card padding, 4px border-radius, primary-700 CTA, gray-50 background, 150ms animation)
UX-DR3: Build custom ConnectionStatus component — WebSocket connection indicator (8px dot: green/yellow/red) with states: connected (silent), reconnecting <5s (yellow dot), reconnecting >5s (yellow + label + toast), disconnected (red + label), reconnected (green + brief toast). ARIA live region for screen readers.
UX-DR4: Build custom ServiceHealthCard component — Per-service health display with status indicator (green check/yellow warning/red X), p95 response time in metric font, expandable section (CPU%, Memory%, connection pool, Kafka consumer lag). Click to expand/collapse. Thresholds: healthy <200ms, degraded 200-1000ms, down >1000ms.
UX-DR5: Build custom OrderStateMachine component — Visual horizontal flow diagram of order states (PENDING → PAYMENT_PENDING → CONFIRMED → SHIPPED → DELIVERED) with current state highlighted + pulse animation, completed states with checkmark, failed states with X + reason tooltip. Two variants: Customer (friendly labels, larger, timestamps) and Admin (technical labels on hover, compact, saga step detail). ARIA progressbar role.
UX-DR6: Build custom DegradationBanner component — Full-width banner below Customer Website header. Three tiers: hidden (all healthy), Partial (yellow background, info icon, "Some features are temporarily limited" with affected features list, dismissible per session), Maintenance (full-page overlay, not dismissible). ARIA alert role with assertive live region.
UX-DR7: Build custom EmptyState component — Illustrated placeholder with SVG line art, title, description, and CTA button. Variants for: Product list Admin ("No products yet" / "Add First Product"), Orders Customer ("No orders yet" / "Start Shopping"), Cart ("Your cart is empty" / "Browse Products"), Search results ("No results found" / "Clear Filters"), DLQ Admin ("No unprocessed events" / success state), Alerts Admin ("All clear" / success state). Illustration aria-hidden, CTA keyboard focusable.
UX-DR8: Build custom KafkaLagIndicator component — Consumer group name, current lag count, mini sparkline chart (last 5min), status badge. States: Healthy (<100 msgs, green), Elevated (100-1000, yellow), Critical (>1000, red with alert). ARIA label with group name, count, and status.
UX-DR9: Implement Customer Website navigation — Fixed top header with Logo (left), search bar (center, prominent, AutoComplete with 200ms debounce), Cart icon with Badge + User menu (right). Horizontal category nav below header. Breadcrumbs below categories. Product grid: 4 columns at 1280px+, 3 at 1024px. Product cards: image (1:1) + title + price + Rating + stock Tag + "Add to Cart" on hover.
UX-DR10: Implement Admin Dashboard navigation — Collapsible left sidebar (240px → 56px) with icon + text labels, grouped sections ("Operations": Dashboard/Orders/Products/Inventory, "System": Health/Events/Reports). Fixed top header with breadcrumb + "⌘K" hint + notifications + user. Command palette (Cmd+K) via Dialog + AutoComplete for quick navigation across entities.
UX-DR11: Implement Customer checkout flow — 4-step Stepper (Cart Review → Shipping Address → Payment → Confirm) with persistent order summary sidebar on right. Inline validation on blur (VeeValidate + Yup). Saga processing shown as progress overlay with micro-story text ("Creating your order..." → "Reserving items..." → "Processing payment..."). Success: confirmation page with order number, summary, email promise. Failure: empathetic message, order/cart preserved, clear next action.
UX-DR12: Implement Admin Dashboard overview — 4 metric cards row (Orders Today, Revenue, Low Stock, System Health) with semantic colors and count-up animation on load. "Needs Attention" section with priority-sorted alert cards with inline action buttons. Skeleton screens for loading (never spinners). TabView for "Business" / "System" tabs.
UX-DR13: Implement Admin data tables pattern — PrimeVue DataTable with sortable/filterable columns, inline cell editing, row selection with checkbox, bulk action toolbar on selection, slide-over detail panels (Sidebar component, right position, half-width), 25 rows default pagination (10/25/50/100 options), skeleton loading rows, EmptyState in table body.
UX-DR14: Implement toast notification system — PrimeVue Toast at bottom-right position. Success: checkmark, success tint, 3s auto-dismiss. Error: X circle, error tint, sticky (manual dismiss only). Warning: triangle, warning tint, 5s. Info: info circle, info tint, 4s. Max 3 stacked. Error toasts never auto-dismissed. Action buttons when applicable (Undo, Retry, View).
UX-DR15: Implement button hierarchy — Primary (solid primary-600/700, 1 per view), Secondary (outlined primary-600), Text (no border, text color), Danger (solid error-500, always with confirmation dialog), Ghost (transparent, icon-only or minimal). Loading state: disabled + spinner + "Processing..." text. Min 40px height (Customer), 32px (Admin).
UX-DR16: Implement form patterns — Labels above inputs, required fields unlabeled (optional fields marked "(optional)"), single-column Customer forms, two-column Admin forms. Input states: default (gray-200 border), focus (primary-500 border + primary-50 ring), error (error-500 border + message below), disabled (gray-100 bg), read-only (no border).
UX-DR17: Implement accessibility requirements — WCAG 2.1 AA compliance. Color contrast ratios meeting AA standards. All interactive elements 44x44px minimum. 2px primary-500 focus outline on all focusable elements. Semantic HTML landmarks. ARIA labels on icon-only buttons. aria-live regions for toasts and alerts. Skip-to-main-content link. prefers-reduced-motion support. eslint-plugin-vuejs-accessibility. axe-core integration for automated testing.
UX-DR18: Implement Admin real-time feed — Live WebSocket events panel (sidebar or dedicated panel) with events streaming in via STOMP. New events slide-in with subtle animation, auto-scroll when not paused. Pause/Resume control, filter by type, "N new events" badge when paused. WebSocket reconnection with missed-event backfill.
UX-DR19: Implement Customer search experience — AutoComplete in header after 2 chars with product thumbnail + name + price dropdown (max 5 suggestions). Search results in DataView grid with collapsible left sidebar filters (checkboxes for brand/category, range slider for price, star selector for rating). Active filters as removable Tag chips. "Load more" button pagination for search, numbered pages for order history.
UX-DR20: Implement modal and overlay patterns — Confirmation dialog for destructive actions (no backdrop dismiss), Login modal for auth (social login prominent), Slide-over for Admin detail views (right, half-width, backdrop click closes), Command palette (centered overlay, auto-focused search). Max 1 overlay at a time. Esc closes topmost. Focus trapped inside while open.

### FR Coverage Map

FR1: Epic 1 - Browse products by category
FR2: Epic 1 - Search products by keyword
FR3: Epic 1 - Filter search results
FR4: Epic 1 - View product detail
FR5: Epic 1 - Sync product data PostgreSQL → Elasticsearch
FR6: Epic 1 - GraphQL product queries
FR7: Epic 2 - Add products to cart
FR8: Epic 2 - Update cart quantity
FR9: Epic 2 - Remove products from cart
FR10: Epic 2 - View cart summary with total
FR11: Epic 2 - Persist cart across sessions
FR12: Epic 2 - Cart TTL expiry
FR13: Epic 6 - Cart expiry email notification
FR14: Epic 4 - Place order from cart
FR15: Epic 4 - Saga pattern order coordination
FR16: Epic 4 - Compensating transactions
FR17: Epic 4 - View order history and details
FR18: Epic 4 - Track order status changes
FR19: Epic 4 - Order state machine
FR20: Epic 4 - Cancel order (PENDING/CONFIRMED)
FR21: Epic 4 - Saga compensation on cancellation
FR22: Epic 4 - Race condition cancellation vs payment
FR23: Epic 4 - Atomic inventory reservation
FR24: Epic 5 - Admin view stock levels
FR25: Epic 5 - Admin restock quantities
FR26: Epic 5 - Low-stock alerts generation
FR27: Epic 4 - Release inventory on cancel/fail
FR28: Epic 4 - Mock payment processing
FR29: Epic 4 - Payment idempotency
FR30: Epic 4 - Payment retry with backoff
FR31: Epic 4 - Auto refund on compensation
FR32: Epic 4 - Graceful payment unavailability
FR33: Epic 6 - Order confirmation notification
FR34: Epic 6 - Payment success/failure notification
FR35: Epic 6 - Low-stock alerts to admin
FR36: Epic 6 - Kafka-driven notification delivery
FR37: Epic 3 - Keycloak email/password login
FR38: Epic 3 - Social login (Google, GitHub)
FR39: Epic 3 - Admin Keycloak login
FR40: Epic 3 - RBAC enforcement
FR41: Epic 3 - JWT validation at API Gateway
FR42: Epic 3 - Token refresh management
FR43: Epic 5 - Admin product CRUD
FR44: Epic 5 - Admin order management with filters
FR45: Epic 5 - Admin order detail view
FR46: Epic 7 - Real-time order events (WebSocket)
FR47: Epic 7 - Real-time inventory alerts (WebSocket)
FR48: Epic 7 - System health via WebSocket
FR49: Epic 7 - CQRS-powered reports
FR50: Epic 3 - API Gateway routing
FR51: Epic 4 - gRPC inter-service communication
FR52: Epic 4 - Kafka async events
FR53: Epic 8 - Circuit Breaker
FR54: Epic 6 - Dead Letter Queue
FR55: Epic 4 - Outbox Pattern
FR56: Epic 8 - Graceful degradation
FR57: Epic 8 - Graceful shutdown
FR58: Epic 9 - Distributed tracing
FR59: Epic 9 - Correlation ID propagation
FR60: Epic 9 - Health check endpoints
FR61: Epic 8 - Rate limiting at API Gateway
FR62: Epic 9 - Centralized configuration
FR63: Epic 1 - Flyway database migrations
FR64: Epic 2 - Redis caching with TTL
FR65: Epic 2 - Event-driven cache invalidation
FR66: Epic 7 - API response aggregation
FR67: Epic 7 - Event sourcing (Order Service)
FR68: Epic 7 - CQRS read model rebuild
FR69: Epic 9 - Service discovery
FR70: Epic 7 - DLQ management UI
FR71: Epic 9 - Data reconciliation jobs
FR72: Epic 9 - Audit trail
FR73: Epic 1+ - Pagination and sorting (across epics)
FR74: Epic 5 - Product image upload

## Epic List

### Epic 1: Project Foundation & Product Discovery
Customer can browse, search, and view product details on a fully operational microservices platform. This epic establishes the entire project infrastructure (monorepo, Maven multi-module, shared libraries, Docker Compose, Flyway migrations) and delivers the first vertical slice — Product Service with Elasticsearch-powered search and Customer Website product browsing UI.
**FRs covered:** FR1, FR2, FR3, FR4, FR5, FR6, FR63, FR73 (products)

### Epic 2: Shopping Cart Experience
Customer can manage a shopping cart — add/remove products, update quantities, view totals — with cart data persisting across browser sessions via Redis. System handles cart TTL expiry and provides cached product data with event-driven invalidation.
**FRs covered:** FR7, FR8, FR9, FR10, FR11, FR12, FR64, FR65

### Epic 3: User Authentication & Authorization
Customer can register and log in via Keycloak (email/password or social login with Google/GitHub). Admin can log in with admin role. System enforces role-based access control at API Gateway with JWT validation, token refresh, and route-level permissions for Customer vs Admin paths.
**FRs covered:** FR37, FR38, FR39, FR40, FR41, FR42, FR50

### Epic 4: Order Processing & Distributed Transactions
Customer can place orders from cart, track order status, and cancel orders. System coordinates order creation across services using Saga pattern (Order → Inventory → Payment) with compensating transactions, distributed locking for inventory, idempotent payments, and graceful handling of payment unavailability. Full gRPC inter-service communication and Kafka async events with Outbox Pattern.
**FRs covered:** FR14, FR15, FR16, FR17, FR18, FR19, FR20, FR21, FR22, FR23, FR27, FR28, FR29, FR30, FR31, FR32, FR51, FR52, FR55

### Epic 5: Admin Product & Inventory Management
Admin can perform full CRUD on products (including image upload), manage inventory (view stock levels, restock, receive low-stock alerts), and view/manage all orders with filtering by status and detailed order views.
**FRs covered:** FR24, FR25, FR26, FR43, FR44, FR45, FR74

### Epic 6: Notifications & Event-Driven Communication
System sends notifications across all channels — order confirmation, payment status, cart expiry reminders, and low-stock alerts to admin — all via event-driven Kafka consumers. Failed events are captured in Dead Letter Queue for later reprocessing.
**FRs covered:** FR13, FR33, FR34, FR35, FR36, FR54

### Epic 7: Admin Real-Time Dashboard & Reporting
Admin can monitor real-time order events and inventory alerts via WebSocket, view system health status, access CQRS-powered reports (revenue, top products, order trends by time range), manage DLQ messages with retry capability, and view aggregated cross-service data.
**FRs covered:** FR46, FR47, FR48, FR49, FR66, FR67, FR68, FR70

### Epic 8: System Resilience & Graceful Degradation
System detects service failures and opens circuit breakers to prevent cascading failures. Graceful degradation across 3 tiers when services are unavailable. Rate limiting at API Gateway. Graceful shutdown for all services. Saga Phase B hardening (idempotent steps, dead saga detection, per-step timeouts).
**FRs covered:** FR53, FR56, FR57, FR61

### Epic 9: Observability & Operations
Developer/DevOps can trace every request end-to-end with distributed tracing, view structured logs with correlation IDs, monitor health checks, detect data inconsistencies via reconciliation jobs, and audit all state-changing operations. Centralized configuration and dynamic service discovery.
**FRs covered:** FR58, FR59, FR60, FR62, FR69, FR71, FR72

### Epic 10: Comprehensive Testing & Quality Assurance
Complete testing pyramid validates all distributed patterns — unit tests, integration tests (Testcontainers), contract tests (Pact REST, Protobuf gRPC, Schema Registry Kafka), E2E tests, performance tests (k6), and chaos tests. CI/CD pipelines automate build, test, and deployment with quality gates.
**FRs covered:** Cross-cutting — validates all FRs through NFR49-NFR62

---

## Epic 1: Project Foundation & Product Discovery

Customer can browse, search, and view product details on a fully operational microservices platform. This epic establishes minimal project infrastructure (monorepo, Maven multi-module, common-lib, Docker Compose with only required containers) and delivers the first vertical slice — Product Service with Elasticsearch-powered search and Customer Website product browsing UI. Infrastructure is added incrementally — only what this epic needs.

### Story 1.1: Scaffold Monorepo & Minimal Development Infrastructure

As a developer,
I want a scaffolded monorepo with Maven multi-module backend, npm workspaces frontend, common-lib foundation, and Docker Compose with minimal containers,
So that I can begin developing the first vertical slice (Product Service) with all foundational tooling in place.

**Acceptance Criteria:**

**Given** a new repository
**When** I run Docker Compose
**Then** only 5 containers start: PostgreSQL (product_db), Elasticsearch, Kafka (KRaft), Schema Registry, and a Kafka UI (optional) — no Redis, no Keycloak, no additional PostgreSQL instances yet
**And** total memory footprint is ~3-4GB

**Given** the Maven multi-module project
**When** I run `mvn compile` from backend/
**Then** parent POM (with spring-boot-starter-parent, Spring Cloud BOM, maven-enforcer-plugin) and all declared modules compile without errors
**And** Maven Wrapper (mvnw) is included for reproducible builds

**Given** common-lib module
**When** referenced by a service module
**Then** it provides: ApiResponse, ApiErrorResponse, PagedResponse DTOs, PaginationMeta, ErrorCode enum, exception hierarchy (RoboMartException → ResourceNotFoundException, BusinessRuleException, ValidationException, ExternalServiceException), GlobalExceptionHandler (@ControllerAdvice), JacksonConfig (camelCase, NON_NULL, ISO dates), LoggingConfig, logback-spring.xml (structured JSON), BaseEntity (id, createdAt, updatedAt), CorrelationIdFilter

**Given** events module
**When** compiled
**Then** it contains only the base Avro event envelope schema (eventId, eventType, aggregateId, aggregateType, timestamp, version, payload) — domain-specific schemas added by later stories

**Given** security-lib, proto, and test-support modules
**When** inspected
**Then** each has a valid pom.xml and empty package structure — no implementation content yet (added incrementally by Epic 3, Epic 4, etc.)

**Given** frontend/ directory
**When** I run `npm install`
**Then** npm workspaces resolve: @robo-mart/shared (empty package structure with package.json), customer-website (placeholder), admin-dashboard (placeholder)

**Given** the project root
**When** inspected
**Then** .editorconfig, .gitignore, backend/config/checkstyle/checkstyle.xml, and infra/docker/ directory exist matching architecture document structure

### Story 1.2: Implement Product Service with REST Read API

As a customer,
I want to browse products by category and view product details via REST API,
So that I can discover products available for purchase.

**Acceptance Criteria:**

**Given** Product Service with Flyway migrations
**When** the service starts with `demo` Spring profile active
**Then** products, categories, product_images, and outbox_events tables are created automatically via Flyway versioned migrations (FR63)
**And** ~50 seed products across 5 categories with images, descriptions, prices, and ratings are loaded via Flyway repeatable migration (R__seed_products.sql)

**Given** GET /api/v1/products?categoryId=5&page=0&size=20
**When** the request is made
**Then** response returns paginated products filtered by category in REST wrapper format: `{data: [...], pagination: {page, size, totalElements, totalPages}, traceId}` (FR1, FR73)

**Given** GET /api/v1/products/{productId} with a valid ID
**When** the request is made
**Then** response returns full product detail: name, description, specifications, price, images list, stock availability, category, brand, average rating (FR4)

**Given** GET /api/v1/products/99999 with an invalid ID
**When** the request is made
**Then** response returns 404 with error format: `{error: {code: "PRODUCT_NOT_FOUND", message: "Product with id 99999 not found"}, traceId, timestamp}`

**Given** Product entity and DTOs
**When** mapping between them
**Then** MapStruct ProductMapper handles all entity ↔ DTO conversions (CreateProductRequest, UpdateProductRequest, ProductDetailResponse, ProductListResponse)

**Given** the Product Service source code
**When** inspected
**Then** it follows the prescribed service structure: config/, controller/, dto/, entity/, exception/, mapper/, repository/, service/, event/ packages under com.robomart.product

### Story 1.3: Implement Elasticsearch Integration & Product Sync via Outbox Pattern

As a customer,
I want product data to be searchable via Elasticsearch with changes synced from PostgreSQL within 30 seconds,
So that I can perform fast full-text search across up-to-date product data.

**Acceptance Criteria:**

**Given** Elasticsearch is running
**When** Product Service starts
**Then** Elasticsearch product index is created with proper field mappings (name, description, category, brand, price, rating as searchable/filterable fields)

**Given** the events module
**When** this story is complete
**Then** product Avro schemas are added: product_created.avsc, product_updated.avsc, product_deleted.avsc under events/src/main/avro/product/

**Given** a product exists in PostgreSQL
**When** the Outbox poller runs (1s polling interval, batch size 50)
**Then** the product event is published to Kafka topic `product.product.created` in Avro format via Schema Registry
**And** the outbox record is marked published=true with published_at timestamp

**Given** a product is updated in PostgreSQL
**When** the Outbox poller publishes the update event
**Then** the corresponding Elasticsearch document is updated within 30 seconds of the database change (FR5)

**Given** published outbox events older than 7 days
**When** the daily cleanup job runs
**Then** they are deleted from the outbox_events table

**Given** seed data loaded via `demo` profile
**When** all outbox events are processed
**Then** all ~50 seed products are indexed in Elasticsearch and searchable

### Story 1.4: Implement Product Search with Full-Text & Filtering

As a customer,
I want to search products by keyword and filter results by price, brand, rating, and category,
So that I can quickly find products matching my specific criteria.

**Acceptance Criteria:**

**Given** indexed products in Elasticsearch
**When** GET /api/v1/products/search?keyword=wireless+headphone
**Then** full-text search returns relevant products ranked by relevance score (FR2)
**And** response includes: product name, description snippet, price, brand, average rating, stock status, primary image URL

**Given** search results
**When** filters are applied: ?keyword=headphone&minPrice=50&maxPrice=200&brand=Sony&minRating=4&categoryId=3
**Then** results are narrowed to match ALL applied filters simultaneously (FR3)

**Given** a search request with filters
**When** only some filters are provided (e.g., only minPrice and brand)
**Then** unspecified filters are not applied — partial filtering works correctly

**Given** a search with no matching results
**When** the query returns empty
**Then** response returns `{data: [], pagination: {totalElements: 0, totalPages: 0}, traceId}`

**Given** search results
**When** requesting paginated results with ?page=0&size=20
**Then** pagination metadata (page, size, totalElements, totalPages) is correct (FR73)

**Given** concurrent search requests
**When** multiple users search simultaneously
**Then** Elasticsearch responds within 500ms for 95th percentile (aligned with NFR2)

### Story 1.5: Implement GraphQL Product Endpoint

As a customer,
I want to query products via GraphQL with flexible filtering and nested data,
So that I can retrieve exactly the product data I need in a single request.

**Acceptance Criteria:**

**Given** GraphQL endpoint at /graphql
**When** I send a query `{ product(id: 1) { id name price category { name } images { url } } }`
**Then** I receive only the requested fields with nested category and images resolved (FR6)

**Given** GraphQL endpoint
**When** I query products with filter arguments `{ products(categoryId: 5, minPrice: 10, keyword: "robot") { id name price } }`
**Then** I receive filtered results with only requested fields

**Given** a product query requesting nested data from multiple entities
**When** using @BatchMapping for category and images
**Then** the N+1 problem is avoided — batch loading via DataLoader pattern is used

**Given** GraphQL response
**When** returned to client
**Then** it follows native GraphQL spec format (`{data: {...}}` or `{data: null, errors: [...]}`) — NOT the REST API response wrapper

**Given** the GraphQL schema file
**When** inspected at src/main/resources/graphql/schema.graphqls
**Then** it defines Product, Category, ProductImage types with proper relationships and query entry points

### Story 1.6: Setup Customer Website Foundation & Design System

As a developer,
I want a fully configured Customer Website (Vue.js SPA) with shared design system, PrimeVue, Tailwind, and core UX patterns,
So that all subsequent frontend stories have a consistent, accessible foundation to build upon.

**Acceptance Criteria:**

**Given** create-vue scaffolding
**When** Customer Website starts with `npm run dev`
**Then** it runs with TypeScript, Vue Router, Pinia, Vitest, ESLint + Prettier configured via Vite 8

**Given** @robo-mart/shared package
**When** imported by Customer Website
**Then** it provides design tokens: colors.ts (primary blue palette primary-50 through primary-900, semantic success/warning/error/info with 50 and 500 variants, neutral grays), typography.ts (Inter font, customer 16px base scale), spacing.ts (4px base unit: xs through 3xl) (UX-DR1)

**Given** PrimeVue with customer-theme
**When** the app renders
**Then** Aura preset is active with: 16px base font, 1.6 line-height, 24px card padding, 8px border-radius, primary-600 CTA color, white page background, 200ms animation duration (UX-DR2)

**Given** Tailwind CSS
**When** configured via tailwind.config.ts
**Then** it extends @robo-mart/shared tokens with customer-specific generous spacing values

**Given** the Customer Website layout
**When** viewing the app
**Then** DefaultLayout renders: fixed top header with Logo (left), search bar placeholder (center), Cart icon with Badge + User menu placeholder (right), horizontal category nav below header, `<main>` content area, footer (UX-DR9)

**Given** PrimeVue Toast
**When** configured globally
**Then** toast system works at bottom-right: success (3s auto-dismiss), error (sticky/manual dismiss), warning (5s), info (4s), max 3 stacked (UX-DR14)

**Given** button components
**When** rendered in the app
**Then** they follow hierarchy: Primary (solid primary-600, max 1 per view), Secondary (outlined), Text, Danger, Ghost, with 40px min height and loading state (disabled + spinner) (UX-DR15)

**Given** EmptyState component in @robo-mart/shared
**When** used with variant="search-results"
**Then** it renders SVG line art illustration (aria-hidden), title "No results found", description "Try different keywords or filters", CTA button "Clear Filters" (keyboard focusable) (UX-DR7)

**Given** all interactive elements
**When** navigated via keyboard
**Then** 2px primary-500 focus outline is visible, skip-to-main-content link exists and works, semantic HTML landmarks (`<header>`, `<nav>`, `<main>`, `<footer>`) are present, eslint-plugin-vuejs-accessibility reports no errors (UX-DR17)

**Given** the app with `prefers-reduced-motion: reduce` enabled
**When** animations would normally play
**Then** all transitions are instant (no animation) (UX-DR17)

### Story 1.7: Implement Customer Product Browsing & Search UI

As a customer,
I want to browse products by category, search with autocomplete, filter results, and view product details on the website,
So that I can discover and evaluate products visually before purchasing.

**Acceptance Criteria:**

**Given** the Customer Website homepage
**When** I visit the site
**Then** I see product categories in the horizontal nav and products displayed in a 4-column grid (3 at 1024px) with rich product cards: image (1:1 ratio), title, price, Rating stars, stock Tag badge (green=in stock, yellow=low stock, red=out of stock) (FR1, UX-DR9)

**Given** the search bar in the header
**When** I type 2+ characters
**Then** AutoComplete dropdown appears within 200ms debounce showing up to 5 suggestions with product thumbnail + name + price (UX-DR19)

**Given** search autocomplete
**When** I select a suggestion
**Then** I navigate directly to that product's detail page
**And** when I press Enter instead, I navigate to the search results page

**Given** the search results page
**When** viewing results for a keyword
**Then** I see a DataView grid with collapsible left sidebar containing: checkboxes (brand, category), range slider (price), star selector (rating). Active filters display as removable Tag chips above results. Result count shows "42 results for 'wireless headphone'" (FR2, FR3, UX-DR19)

**Given** filter changes on the search results page
**When** I toggle a filter checkbox or adjust the price slider
**Then** results update instantly without page reload (client-side re-query)

**Given** search results
**When** I scroll to the bottom
**Then** a "Load more" button loads additional results appended to the grid (FR73)

**Given** a product card
**When** I hover with mouse
**Then** subtle shadow elevation appears and "Add to Cart" ghost button is revealed (UX-DR9)
**And** clicking "Add to Cart" shows a Toast placeholder "Cart coming soon" — full cart integration is implemented in Story 2.4 when Cart Service exists

**Given** a product card click
**When** I click the card (not the Add to Cart button)
**Then** I navigate to the product detail page showing: PrimeVue Galleria image gallery with thumbnail navigation, full specs, price, stock status badge with color coding, Rating with review count, breadcrumb (Home > Category > Product Name) (FR4)

**Given** any product page while data is loading
**When** the API request is in progress
**Then** PrimeVue Skeleton components display content-shaped placeholders matching the card/detail layout — never a blank white screen

**Given** search with no matching products
**When** results are empty
**Then** EmptyState component shows: "No results found" / "Try different keywords or filters" / "Clear Filters" CTA (UX-DR7)

**Given** Pinia stores
**When** product data is fetched
**Then** useProductStore manages: product list, search results, filters, loading/error state following Composition API pattern with ref() for state

---

## Epic 2: Shopping Cart Experience

Customer can manage a shopping cart — add/remove products, update quantities, view totals — with cart data persisting across browser sessions via Redis. System handles cart TTL expiry and provides cached product data with event-driven invalidation. This epic adds Redis to Docker Compose.

### Story 2.1: Add Redis to Infrastructure & Implement Cart Service Core

As a customer,
I want to add products to a cart stored in Redis so my cart is fast and persists across page refreshes,
So that I can collect products before purchasing.

**Acceptance Criteria:**

**Given** Docker Compose
**When** updated for this story
**Then** Redis container is added to the core profile (total: 6 containers)

**Given** Cart Service module
**When** created with pom.xml referencing common-lib
**Then** it follows the prescribed service structure: config/, controller/, dto/, entity/, mapper/, repository/, service/ packages under com.robomart.cart

**Given** Cart Service with Redis
**When** I POST /api/v1/cart/items with `{productId, quantity}`
**Then** a cart is created (or updated) in Redis as a hash, and response returns the full cart state with items, quantities, and total price (FR7)

**Given** an existing cart
**When** I PUT /api/v1/cart/items/{itemId} with `{quantity: 3}`
**Then** the item quantity is updated and cart total recalculated (FR8)

**Given** an existing cart with multiple items
**When** I DELETE /api/v1/cart/items/{itemId}
**Then** the item is removed from cart and total recalculated (FR9)

**Given** an existing cart
**When** I GET /api/v1/cart
**Then** response returns cart summary: items list (productId, name, price, quantity, subtotal), total items count, total price (FR10)

**Given** a cart stored in Redis
**When** I close the browser and reopen the same page
**Then** the cart data persists and is retrievable via the same cart identifier

### Story 2.2: Implement Cart Persistence & TTL Expiry

As a customer,
I want my cart to persist across browser sessions when I'm logged in, and expire automatically after 24 hours of inactivity,
So that I don't lose my selections but stale carts are cleaned up.

**Acceptance Criteria:**

**Given** a cart associated with a user identifier (userId parameter)
**When** the same user identifier is used to retrieve the cart in a later session
**Then** cart data is still available in Redis, keyed by userId — the actual login/logout integration becomes fully testable after Epic 3 auth is implemented (FR11)

**Given** a cart in Redis
**When** it has not been accessed for 24 hours (configurable TTL)
**Then** Redis automatically expires the cart key and releases all held references (FR12)

**Given** a cart with TTL set
**When** the user accesses the cart (GET, PUT, POST, DELETE)
**Then** the TTL is reset to the full 24-hour window

**Given** cart TTL configuration
**When** inspected in application.yml
**Then** TTL is configurable via `robomart.cart.ttl-minutes` property (default: 1440 = 24 hours)

### Story 2.3: Implement Redis Caching with Event-Driven Invalidation

As a customer,
I want frequently accessed product data to be cached for fast retrieval, with cache automatically invalidated when products change,
So that I always see current data without slow database queries.

**Acceptance Criteria:**

**Given** Spring Cache + Redis configured on Product Service
**When** a product detail is requested via GET /api/v1/products/{productId}
**Then** the result is cached in Redis with 5-minute TTL (FR64)

**Given** product search results
**When** the same search query is repeated within 1 minute
**Then** cached results are returned from Redis (1-minute TTL for search results) (FR64)

**Given** a product is updated in PostgreSQL
**When** the product.product.updated Kafka event is consumed
**Then** the corresponding cache entries (product detail + affected search results) are invalidated in Redis (FR65)

**Given** a product is deleted
**When** the product.product.deleted Kafka event is consumed
**Then** all cache entries for that product are invalidated (FR65)

**Given** Cart Service
**When** it receives a product.product.updated event (e.g., price change)
**Then** it updates the cached price in any active cart containing that product

### Story 2.4: Implement Customer Cart UI

As a customer,
I want to manage my cart visually on the website — see items, change quantities, remove items, and view totals,
So that I can review and adjust my selections before checkout.

**Acceptance Criteria:**

**Given** the Customer Website header
**When** a product is added to cart
**Then** the Cart icon Badge count updates with subtle animation, and a success Toast appears: "Added to cart" with "Go to Cart" action button (UX-DR14)

**Given** the "Add to Cart" button on a product card or detail page
**When** I click it
**Then** the cart is updated optimistically (UI updates instantly), server confirms in background, and rolls back with error Toast on failure

**Given** the Cart page (/cart)
**When** I navigate to it
**Then** I see all cart items with: product image thumbnail, name, unit price, quantity input (editable), subtotal per item, and cart total at bottom

**Given** a cart item quantity input
**When** I change the quantity to a new valid number
**Then** the subtotal and cart total update immediately (optimistic UI)

**Given** a cart item
**When** I click the remove (X) button
**Then** the item is removed with confirmation Toast, cart total updates

**Given** an empty cart
**When** I visit the Cart page
**Then** EmptyState shows: "Your cart is empty" / "Add items to get started" / "Browse Products" CTA button (UX-DR7)

**Given** the Cart page
**When** data is loading
**Then** Skeleton placeholders matching cart item layout are displayed

**Given** Pinia useCartStore
**When** cart operations are performed
**Then** it manages: items, isLoading, error, totalItems (computed), totalPrice (computed), addItem(), removeItem(), updateQuantity() following Composition API pattern

---

## Epic 3: User Authentication & Authorization

Customer can register and log in via Keycloak (email/password or social login with Google/GitHub). Admin can log in with admin role. System enforces RBAC at API Gateway with JWT validation and token refresh. This epic adds Keycloak to Docker Compose, implements security-lib, and sets up API Gateway.

### Story 3.1: Add Keycloak & API Gateway Infrastructure

As a developer,
I want Keycloak and API Gateway running locally with pre-configured realm, roles, and demo users,
So that authentication and authorization can be developed and tested.

**Acceptance Criteria:**

**Given** Docker Compose
**When** updated for this story
**Then** Keycloak container with PostgreSQL (keycloak_db) is added to core profile (total: 8 containers)

**Given** Keycloak
**When** started with the realm export (infra/docker/keycloak/robomart-realm.json)
**Then** a "robomart" realm is configured with: Customer and Admin roles, email/password login enabled, Google and GitHub identity providers configured (client IDs can be placeholder), JWT access token TTL 15 minutes, refresh token TTL 24 hours

**Given** the Keycloak realm
**When** demo profile is active
**Then** two demo users exist: demo-customer@robomart.com (Customer role) and demo-admin@robomart.com (Admin role)

**Given** API Gateway module (api-gateway)
**When** created with Spring Cloud Gateway
**Then** it includes: RouteConfig (routes for /api/v1/products/**, /api/v1/cart/**, with more routes added by later epics), CorsConfig (allowing both frontend origins), and basic request forwarding to Product Service and Cart Service

**Given** security-lib module
**When** implemented for this story
**Then** it provides: SecurityConfig (base Keycloak OAuth2 resource server config), JwtAuthenticationFilter, AuthContext (extract user ID and roles from SecurityContext), RoleConstants (ROLE_CUSTOMER, ROLE_ADMIN)

### Story 3.2: Implement Customer Registration & Login

As a customer,
I want to register and log in using email/password or social login (Google/GitHub) through Keycloak,
So that I can access personalized features like persistent cart and order history.

**Acceptance Criteria:**

**Given** the Customer Website
**When** I click "Login" in the header
**Then** a Login modal (PrimeVue Dialog) appears with social login buttons (Google, GitHub) prominent at top, and email/password form below (UX-DR20)

**Given** the Login modal
**When** I click "Login with Google"
**Then** I am redirected to Keycloak → Google OAuth2 consent screen, and upon authorization, redirected back to the app with JWT tokens stored (FR38)

**Given** the Login modal
**When** I enter valid email/password and submit
**Then** Keycloak authenticates and returns JWT access + refresh tokens, stored securely in the app (FR37)

**Given** a new user
**When** I click "Register" and complete the registration form
**Then** a new Keycloak account is created with Customer role, and I am logged in automatically (FR37)

**Given** an authenticated user
**When** the JWT access token expires (15 minutes)
**Then** the app silently uses the refresh token to obtain a new access token without interrupting the user session (FR42)

**Given** Pinia useAuthStore
**When** authentication state changes
**Then** it manages: user profile, JWT tokens, isAuthenticated (computed), login(), logout(), refreshToken() actions

### Story 3.3: Implement API Gateway JWT Validation & RBAC

As a system,
I want the API Gateway to validate JWT tokens and enforce role-based access control on every request,
So that protected endpoints are only accessible by authorized users.

**Acceptance Criteria:**

**Given** API Gateway with JwtValidationFilter
**When** a request arrives for a protected endpoint (e.g., /api/v1/orders/**)
**Then** the Gateway validates the JWT token from the Authorization header, and rejects with 401 if missing or invalid (FR41)

**Given** a valid JWT with Customer role
**When** accessing /api/v1/admin/** endpoints
**Then** the Gateway returns 403 Forbidden — customers cannot access admin endpoints (FR40, NFR17)

**Given** a valid JWT with Admin role
**When** accessing /api/v1/admin/** endpoints
**Then** the request is forwarded to the appropriate backend service

**Given** public endpoints (/api/v1/products/**, /graphql)
**When** accessed without a JWT
**Then** the request is forwarded normally — no authentication required (FR50)

**Given** /api/v1/cart/** endpoints
**When** accessed without a JWT
**Then** the request is allowed with an anonymous cart identifier (anonymous cart supported)

**Given** API Gateway route configuration
**When** inspected
**Then** routes are defined per architecture: products (public), cart (optional auth), orders (Customer required), admin/* (Admin required), graphql (public), auth (public)

### Story 3.4: Implement Anonymous Cart Merge on Login

As a customer,
I want my anonymous cart to be preserved and merged with my account when I log in,
So that I don't lose items I added before logging in.

**Acceptance Criteria:**

**Given** a customer browsing without login
**When** they add items to cart
**Then** an anonymous cart is created in Redis with a session-based identifier

**Given** an anonymous cart with 3 items
**When** the customer logs in (via any method: email, Google, GitHub)
**Then** the anonymous cart items are merged into the authenticated user's cart via CartMergeService

**Given** the authenticated user already has a cart with 2 items
**When** anonymous cart is merged
**Then** items are combined — duplicate products have quantities summed, unique products are added, and the anonymous cart is deleted from Redis

**Given** the Customer Website login flow
**When** login completes and cart merge happens
**Then** the Cart icon Badge updates to reflect the merged total, and the user is redirected to their previous page (checkout or browsing)

---

## Epic 4: Order Processing & Distributed Transactions

Customer can place orders from cart, track order status, and cancel orders. System coordinates order creation across services using Saga pattern with compensating transactions, distributed locking for inventory, idempotent payments, and Outbox Pattern. This epic adds PostgreSQL instances for order/inventory/payment services, implements gRPC communication, and delivers the core Saga orchestrator.

### Story 4.1: Add Order Infrastructure — Services, Databases & gRPC

As a developer,
I want Order Service, Inventory Service, and Payment Service scaffolded with their databases and gRPC contracts,
So that the Saga-based order flow can be implemented.

**Acceptance Criteria:**

**Given** Docker Compose
**When** updated for this story
**Then** 3 additional PostgreSQL instances are added: order_db, inventory_db, payment_db (total: ~11 containers)

**Given** proto module
**When** implemented for this story
**Then** it contains: inventory_service.proto (ReserveInventory, ReleaseInventory RPCs), payment_service.proto (ProcessPayment, RefundPayment RPCs), order_service.proto (CreateOrder, GetOrder, CancelOrder RPCs), common/types.proto (Money, Address messages)
**And** `buf lint` validates backward-compatible Protobuf conventions

**Given** Order Service module
**When** created with Flyway migrations
**Then** tables are created: orders, order_items, order_status_history, outbox_events, saga_audit_log
**And** seed data (demo profile) loads sample orders in various states (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED)

**Given** Inventory Service module
**When** created with Flyway migrations
**Then** tables are created: inventory_items, stock_movements, outbox_events
**And** seed data loads inventory quantities matching the ~50 seed products

**Given** Payment Service module
**When** created with Flyway migrations
**Then** tables are created: payments, idempotency_keys, outbox_events

**Given** events module
**When** updated for this story
**Then** Avro schemas added: order/ (order_created, order_status_changed, order_cancelled), inventory/ (stock_reserved, stock_released, stock_low_alert), payment/ (payment_processed, payment_refunded)

**Given** all three services
**When** started
**Then** each exposes gRPC endpoints and follows the prescribed service structure (FR51)

### Story 4.2: Implement Inventory Service with Distributed Locking

As a system,
I want the Inventory Service to reserve and release stock atomically using Redis distributed locks,
So that concurrent orders never cause overselling.

**Acceptance Criteria:**

**Given** Inventory Service with Redis distributed lock (RedisLockConfig)
**When** a ReserveInventory gRPC call is received for productId=1, quantity=2
**Then** a Redis lock is acquired on key `inventory:lock:product:1` with 10-second TTL (NFR36), stock is decremented atomically, and a stock_reserved event is published via Outbox

**Given** 100 concurrent ReserveInventory requests for the same product with only 1 unit in stock
**When** all requests compete for the lock
**Then** exactly 1 request succeeds (stock 1→0), remaining 99 receive InsufficientStockException — no overselling (FR23, NFR6)

**Given** a successful inventory reservation
**When** the lock TTL expires before the transaction completes
**Then** the system detects the expired lock and executes compensating action (release reservation) (NFR36)

**Given** a ReleaseInventory gRPC call
**When** received for a previously reserved order
**Then** stock is incremented back, a stock_released event is published via Outbox (FR27)

**Given** stock level falling below threshold (default: 10 units)
**When** a reservation reduces stock below threshold
**Then** a stock_low_alert Kafka event is published (FR26)

**Given** StockMovement entity
**When** any stock change occurs (reserve, release, restock)
**Then** a StockMovement audit record is created with: type, quantity, orderId, timestamp

### Story 4.3: Implement Payment Service with Idempotency & Retry

As a system,
I want the Payment Service to process mock payments with idempotency guarantees and retry support,
So that payments are never duplicated and temporary failures are handled gracefully.

**Acceptance Criteria:**

**Given** a ProcessPayment gRPC call with an idempotency key
**When** the payment is processed for the first time
**Then** MockPaymentGateway simulates payment processing, a Payment record is created, the idempotency key is stored with 24-hour TTL, and a payment_processed event is published via Outbox (FR28, FR29, NFR45)

**Given** a duplicate ProcessPayment call with the same idempotency key
**When** received within 24 hours
**Then** the original payment result is returned without reprocessing — no duplicate charge (FR29)

**Given** a payment that fails transiently
**When** retry is triggered
**Then** exponential backoff is applied: max 3 retries, initial delay 1 second, backoff multiplier 2x (FR30)

**Given** a RefundPayment gRPC call
**When** received for a completed payment
**Then** the payment is refunded via MockPaymentGateway, payment status updated to REFUNDED, and a payment_refunded event is published via Outbox (FR31)

**Given** Payment Service is unavailable
**When** the gRPC call times out
**Then** the caller receives an UNAVAILABLE gRPC status, and the order should be held in PAYMENT_PENDING state (FR32)

### Story 4.4: Implement Order Saga Orchestrator (Phase A — Core Flow)

As a customer,
I want to place an order that coordinates inventory reservation and payment processing reliably,
So that my order either completes fully or rolls back cleanly with no inconsistent state.

**Acceptance Criteria:**

**Given** Order Service with OrderSagaOrchestrator
**When** a customer places an order via POST /api/v1/orders with cart contents
**Then** an Order is created in PENDING state, SagaStep interface executes: ReserveInventoryStep → ProcessPaymentStep, and the order progresses through states: PENDING → INVENTORY_RESERVING → PAYMENT_PROCESSING → CONFIRMED (FR14, FR15, FR19)

**Given** the Saga happy path completes
**When** both inventory reservation and payment succeed
**Then** order status changes to CONFIRMED, an order.order.status-changed Kafka event is published via Outbox, and order status history records all transitions with timestamps

**Given** payment fails during Saga execution
**When** ProcessPaymentStep returns failure
**Then** compensating transaction executes: ReleaseInventoryStep releases reserved stock, order status changes to CANCELLED, all compensation steps are logged in saga_audit_log (FR16)

**Given** inventory reservation fails (out of stock)
**When** ReserveInventoryStep returns failure
**Then** Saga stops immediately — no payment is attempted, order status changes to CANCELLED with reason "Insufficient stock"

**Given** Saga state
**When** the Order Service restarts mid-saga
**Then** Saga state is recovered from the database (persisted state machine), and the saga resumes or compensates from the last known state

**Given** the Saga audit log
**When** inspected for a completed order
**Then** it contains: saga ID, order ID, each step executed (name, status, timestamp, duration), compensation steps if any

### Story 4.5: Implement Order Cancellation with Saga Compensation

As a customer,
I want to cancel an order in PENDING or CONFIRMED status,
So that I can change my mind and receive a refund automatically.

**Acceptance Criteria:**

**Given** an order in PENDING status
**When** I send POST /api/v1/orders/{orderId}/cancel
**Then** the order transitions to CANCELLED, any reserved inventory is released, and an order.order.cancelled event is published (FR20)

**Given** an order in CONFIRMED status (payment completed)
**When** I cancel the order
**Then** Saga compensation executes: CONFIRMED → PAYMENT_REFUNDING → INVENTORY_RELEASING → CANCELLED. Payment is refunded, inventory is released, cancellation notification event is published (FR21)

**Given** a cancel request arriving simultaneously with an in-flight payment
**When** payment completes before cancellation is processed
**Then** the system detects the completed payment and issues an automatic refund (FR22)

**Given** a cancel request arriving simultaneously with an in-flight payment
**When** cancellation is processed before payment completes
**Then** the payment request is rejected by Payment Service (idempotency key marked as cancelled) (FR22)

**Given** an order in SHIPPED or DELIVERED status
**When** I attempt to cancel
**Then** the system returns 409 Conflict with error code ORDER_NOT_CANCELLABLE

### Story 4.6: Implement Customer Order Tracking UI

As a customer,
I want to view my order history and track the status of each order,
So that I know the progress of my purchases.

**Acceptance Criteria:**

**Given** an authenticated customer
**When** I navigate to /orders (My Orders page)
**Then** I see a list of my orders sorted by date descending, each showing: order number, date, total, status badge (color-coded Tag), item count (FR17)

**Given** an order in the list
**When** I click to view details
**Then** I see: order items (product image, name, quantity, price), shipping address, payment status, order total, and OrderStateMachine component showing current state in the flow (FR18, UX-DR5)

**Given** OrderStateMachine component (Customer variant)
**When** displaying an order in CONFIRMED status
**Then** it shows a horizontal flow: "Order received" ✓ → "Processing payment" ✓ → "Order confirmed" (active, highlighted with primary color + pulse) → "Shipped" (gray) → "Delivered" (gray) with timestamps on completed steps (UX-DR5)

**Given** a CANCELLED order
**When** viewed in OrderStateMachine
**Then** the failed step shows a red X with tooltip showing the cancellation reason (UX-DR5)

**Given** an order in PAYMENT_PENDING status
**When** displayed to the customer
**Then** the status shows "Processing payment" with reassuring text: "We're confirming your payment — we'll notify you when it's done" — not technical "PAYMENT_PENDING"

**Given** order history with numbered pagination
**When** I navigate between pages
**Then** numbered page controls are displayed (not "Load more") (FR73)

### Story 4.7: Implement Customer Checkout Flow UI

As a customer,
I want a smooth checkout experience from cart to order confirmation,
So that I can complete my purchase with confidence.

**Acceptance Criteria:**

**Given** the Cart page with items
**When** I click "Proceed to Checkout"
**Then** if not authenticated, a Login modal appears (social login prominent); if authenticated, I proceed to checkout Step 1

**Given** the Checkout page
**When** it loads
**Then** I see a 4-step Stepper (PrimeVue Stepper): Cart Review → Shipping Address → Payment → Confirm, with a persistent order summary sidebar on the right showing items, quantities, and total (UX-DR11)

**Given** Step 1 (Cart Review)
**When** displayed
**Then** I can edit quantities or remove items, subtotals update in real-time, "Continue" button advances to Step 2

**Given** Step 2 (Shipping Address)
**When** I fill out the address form
**Then** inline validation fires on blur for each field (VeeValidate + Yup), errors show below fields in red, form validates before advancing to Step 3 (UX-DR16)

**Given** Step 3 (Payment)
**When** displayed
**Then** a Stripe-inspired card form (mock) with inline field validation is shown. Card number, expiry, CVV fields validate format as user types (UX-DR11)

**Given** Step 4 (Confirm)
**When** displayed
**Then** full order summary shown: items, shipping address, payment method, total. Single "Place Order" primary CTA button (UX-DR15)

**Given** "Place Order" clicked
**When** Saga processing begins
**Then** a processing overlay appears with micro-story progress text: "Creating your order..." → "Reserving items..." → "Processing payment..." (max 3 seconds per NFR3) (UX-DR11)

**Given** Saga completes successfully
**When** order is CONFIRMED
**Then** Order Confirmation page shows: order number, items summary, estimated delivery, "Track Order" CTA, "Continue Shopping" secondary action, confetti/success animation

**Given** Saga fails (payment declined)
**When** the error is returned
**Then** empathetic message: "Payment couldn't be processed. Your order is saved — try again or use a different method." Cart is preserved, back to Step 3 (UX-DR11)

**Given** Saga fails (out of stock)
**When** the error is returned
**Then** message: "Sorry, [item name] just sold out. Your other items are still in cart." Redirect to Cart page with remaining items

---

## Epic 5: Admin Product & Inventory Management

Admin can perform full CRUD on products (including image upload), manage inventory (view stock, restock, low-stock alerts), and view/manage all orders. This epic creates the Admin Dashboard Vue.js app with its design system and core admin UX patterns.

### Story 5.1: Setup Admin Dashboard Foundation & Design System

As an admin,
I want a dedicated Admin Dashboard application with its own design system optimized for data-dense operations,
So that I have an efficient tool for daily operations management.

**Acceptance Criteria:**

**Given** create-vue scaffolding for admin-dashboard
**When** the app starts with `npm run dev`
**Then** it runs with TypeScript, Vue Router (with admin role guard), Pinia, Vitest, ESLint + Prettier

**Given** admin-theme from @robo-mart/shared
**When** applied to PrimeVue
**Then** Aura preset is active with: 14px base font, 1.4 line-height, 16px card padding, 4px border-radius, primary-700 CTA, gray-50 page background, 150ms animation (UX-DR2)

**Given** the Admin Dashboard layout
**When** viewing the app
**Then** AdminLayout renders: collapsible left sidebar (240px → 56px) with icon + text labels, grouped sections ("Operations": Dashboard/Orders/Products/Inventory, "System": Health/Events/Reports), fixed top header with breadcrumb + "⌘K" hint + notifications + user menu (UX-DR10)

**Given** Cmd+K keyboard shortcut
**When** pressed anywhere in the Admin Dashboard
**Then** command palette (PrimeVue Dialog + AutoComplete) opens centered, search input auto-focused, allowing search across entities (orders, products, pages) (UX-DR10, UX-DR20)

**Given** Admin DataTable pattern
**When** rendering any list view
**Then** it supports: sortable/filterable columns, inline cell editing, row selection with checkbox, bulk action toolbar on selection, 25 rows default pagination (10/25/50/100 options), skeleton loading rows, EmptyState in table body (UX-DR13)

**Given** slide-over detail panel pattern
**When** triggered from a table row
**Then** PrimeVue Sidebar opens from right, half-width, with close on backdrop click or Esc (UX-DR20)

### Story 5.2: Implement Admin Product CRUD

As an admin,
I want to create, read, update, and delete products from the Admin Dashboard,
So that I can manage the product catalog efficiently.

**Acceptance Criteria:**

**Given** the Products page in Admin Dashboard
**When** I navigate to it
**Then** I see a PrimeVue DataTable with columns: ID, Image (thumbnail), Name, Category, Price, Stock, Status, Actions — sortable and filterable (FR43)

**Given** the Products DataTable
**When** I click "Add Product" button
**Then** a Product form page opens with fields: name, description, category (Dropdown), price, brand, specifications — with inline validation on blur (UX-DR16)

**Given** a product in the DataTable
**When** I click the price or stock cell
**Then** it becomes inline editable — Enter to save, Esc to cancel. Toast confirms: "Product updated" (UX-DR13)

**Given** a product row Actions column
**When** I click "Edit"
**Then** a slide-over panel opens with the full product edit form, pre-populated with current values

**Given** a product row Actions column
**When** I click "Delete"
**Then** a ConfirmDialog appears: "Delete [product name]? This cannot be undone." with Danger button. On confirm, product is soft-deleted and row removed with Toast (UX-DR15, UX-DR20)

**Given** the Products DataTable with no products
**When** the table is empty
**Then** EmptyState shows: "No products yet" / "Start building your catalog" / "Add First Product" CTA (UX-DR7)

### Story 5.3: Implement Product Image Upload

As an admin,
I want to upload and manage product images,
So that products have visual representation for customers.

**Acceptance Criteria:**

**Given** the Product form (create or edit)
**When** I use the ProductImageUpload component (PrimeVue FileUpload)
**Then** I can upload images in JPEG, PNG, WebP formats with max 5MB per file (FR74)

**Given** image upload
**When** I select files
**Then** up to 10 images per product can be uploaded with drag-and-drop support and thumbnail previews (FR74)

**Given** uploaded images
**When** viewing the product form
**Then** I can reorder images (first image = primary), delete individual images, and see upload progress

**Given** image storage
**When** images are uploaded
**Then** they are stored in local filesystem (with path configured via `robomart.product.image-storage-path`) and URL references saved in product_images table

### Story 5.4: Implement Admin Inventory Management

As an admin,
I want to view stock levels, restock products, and see low-stock alerts,
So that I can ensure products remain available for customers.

**Acceptance Criteria:**

**Given** the Inventory page in Admin Dashboard
**When** I navigate to it
**Then** I see a DataTable with columns: Product Name, SKU, Current Stock, Reserved, Available, Threshold, Status — with low-stock rows highlighted in warning color (FR24)

**Given** an inventory item in the DataTable
**When** I click the Stock cell
**Then** it becomes an inline number editor. I enter the restock quantity, press Enter, and Toast confirms: "Stock updated — [product] now has [N] units" (FR25)

**Given** bulk restock
**When** I select multiple rows via checkboxes
**Then** a bulk action toolbar appears with "Restock Selected" button. Clicking it opens a form to enter quantity, applying to all selected items

**Given** low-stock products (stock < threshold)
**When** viewing the inventory table
**Then** rows are highlighted with warning-50 background, and a Status column Tag shows "Low Stock" in warning color (FR26)

**Given** the Inventory page
**When** Pinia useInventoryStore loads data
**Then** it manages: inventory items, filters, loading/error state, restockItem(), bulkRestock() actions

### Story 5.5: Implement Admin Order Management

As an admin,
I want to view and manage all orders with status filtering and detailed order views,
So that I can monitor and process customer orders efficiently.

**Acceptance Criteria:**

**Given** the Orders page in Admin Dashboard
**When** I navigate to it
**Then** I see a DataTable with columns: Order #, Customer, Date, Items Count, Total, Status (Tag), Payment Status, Actions — sorted by date descending (FR44)

**Given** the Orders DataTable
**When** I use the Status filter (multi-select Dropdown)
**Then** orders are filtered to show only selected statuses (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED) (FR44)

**Given** an order row
**When** I click "View" in Actions column
**Then** a slide-over panel opens showing: order items (product, quantity, price), customer info, shipping address, payment status, order timeline (PrimeVue Timeline with status history and timestamps) (FR45)

**Given** the order detail slide-over
**When** viewing OrderStateMachine (Admin variant)
**Then** technical Saga states are available on hover (e.g., hover "Processing payment" shows "PAYMENT_PROCESSING"), compact layout, saga step detail visible (UX-DR5)

**Given** the Orders DataTable
**When** I click an inline status Dropdown on an order row
**Then** I can update the status (e.g., CONFIRMED → SHIPPED) with Toast confirmation

---

## Epic 6: Notifications & Event-Driven Communication

System sends notifications across all channels — order confirmation, payment status, cart expiry reminders, and low-stock alerts to admin — all via Kafka consumers. Failed events are captured in Dead Letter Queue for later reprocessing. This epic creates the Notification Service.

### Story 6.1: Implement Notification Service Core & Order Notifications

As a customer,
I want to receive notifications when my order is confirmed, payment succeeds or fails,
So that I stay informed about my purchase status.

**Acceptance Criteria:**

**Given** Notification Service module
**When** created with Flyway migration
**Then** notification_log table is created to store all sent notifications (type, recipient, channel, status, content, timestamp)

**Given** an order.order.status-changed Kafka event with status CONFIRMED
**When** consumed by OrderEventConsumer
**Then** an order confirmation notification is sent to the customer (email) with: order number, items summary, total, estimated delivery (FR33)

**Given** a payment.payment.processed Kafka event with success=true
**When** consumed by OrderEventConsumer
**Then** a payment success notification is sent to the customer (FR34)

**Given** a payment.payment.processed Kafka event with success=false
**When** consumed by OrderEventConsumer
**Then** a payment failure notification is sent with empathetic language: "Payment couldn't be processed. Your order is saved." (FR34)

**Given** all notifications sent
**When** inspected in notification_log table
**Then** each has: notification type, recipient user ID, channel (email), delivery status, content, created_at, trace_id

### Story 6.2: Implement Low-Stock Alerts & Cart Expiry Notifications

As an admin,
I want to receive low-stock alerts, and as a customer, I want cart expiry reminders,
So that admins can restock proactively and customers don't lose their carts unexpectedly.

**Acceptance Criteria:**

**Given** an inventory.stock.low-alert Kafka event
**When** consumed by InventoryAlertConsumer
**Then** a low-stock alert notification is sent to all admin users with: product name, current stock, threshold (FR35)

**Given** a customer's cart approaching expiry
**When** 2 hours remain before the 24-hour TTL expires
**Then** Cart Service publishes a cart-expiry-warning event, Notification Service sends email to the customer with: cart contents summary and direct checkout link (FR13)

**Given** all notifications
**When** delivered
**Then** they are delivered via event-driven Kafka consumption — no synchronous notification calls from other services (FR36)

### Story 6.3: Implement Dead Letter Queue for Failed Events

As a system,
I want failed Kafka events to be captured in a Dead Letter Queue for later reprocessing,
So that no events are lost when processing fails.

**Acceptance Criteria:**

**Given** a Kafka consumer in Notification Service
**When** event processing fails after max retries
**Then** the failed event is routed to a DLQ topic (e.g., notification.dlq) with original event payload, error message, stack trace, retry count, and timestamp (FR54)

**Given** DLQ messages
**When** retained in Kafka
**Then** they have a minimum 7-day retention period before expiry (NFR28)

**Given** DlqConsumer in Notification Service
**When** monitoring the DLQ
**Then** it logs failed events with full context (event type, aggregate ID, error, trace ID) for debugging

**Given** DLQ message metadata
**When** inspected
**Then** each includes: original topic, partition, offset, failure reason, retry count, first failure timestamp, last failure timestamp

---

## Epic 7: Admin Real-Time Dashboard & Reporting

Admin can monitor real-time order events and inventory alerts via WebSocket, view system health, access CQRS-powered reports, manage DLQ messages with retry capability, and view aggregated cross-service data. This epic adds WebSocket/STOMP to the Notification Service and builds the real-time Admin Dashboard features.

### Story 7.1: Implement WebSocket Real-Time Event Feed

As an admin,
I want to see live order events and inventory alerts streaming in real-time on my dashboard,
So that I can monitor operations as they happen without refreshing.

**Acceptance Criteria:**

**Given** Notification Service with WebSocketConfig
**When** configured
**Then** Spring WebSocket + STOMP protocol is enabled with SockJS fallback, JWT validated on STOMP CONNECT frame

**Given** an order.order.status-changed Kafka event
**When** consumed by Notification Service
**Then** it is pushed to Admin Dashboard via WebSocket topic /topic/orders with: order ID, status, customer, total, timestamp (FR46)

**Given** an inventory.stock.low-alert Kafka event
**When** consumed by Notification Service
**Then** it is pushed to Admin Dashboard via WebSocket topic /topic/inventory-alerts with: product name, current stock, threshold (FR47)

**Given** the Admin Dashboard Live Feed panel
**When** WebSocket events arrive
**Then** new events slide in with subtle animation at the top, auto-scroll when not paused. Each event shows: type icon, description, timestamp ("2s ago") (UX-DR18)

**Given** the Live Feed panel
**When** I click "Pause"
**Then** auto-scroll stops, a "N new events" badge appears, clicking "Resume" scrolls to newest and resumes auto-scroll (UX-DR18)

**Given** ConnectionStatus component in Admin header
**When** WebSocket is connected
**Then** green dot (8px) indicator is shown, silent. If disconnected >5s, yellow dot + "Reconnecting..." label + toast appears. On reconnect, green dot restored + "Connection restored" brief toast (UX-DR3)

### Story 7.2: Implement Admin Dashboard Overview with Metrics

As an admin,
I want to see key business metrics and alerts at a glance when I open the dashboard,
So that I can quickly assess what needs my attention.

**Acceptance Criteria:**

**Given** the Admin Dashboard page
**When** loaded
**Then** 4 metric cards display above the fold: Orders Today (blue), Revenue Today (green), Low Stock Items (yellow/red), System Health (green/yellow/red) — numbers animate with count-up on load (UX-DR12)

**Given** the "Needs Attention" section below metrics
**When** alerts exist
**Then** priority-sorted alert cards show with severity-coded Tags (red=critical, yellow=warning, blue=info) and inline action buttons: "View", "Quick Restock", "Acknowledge" (UX-DR12)

**Given** a low-stock alert card
**When** I click "Quick Restock"
**Then** an inline number input appears, I enter quantity, click "Update", Toast confirms "Stock updated", alert dismissed

**Given** the Dashboard with TabView
**When** viewing
**Then** "Business" tab (default) shows metrics + alerts + recent orders. "System" tab shows service health (built in Story 7.4) (UX-DR12)

**Given** dashboard data loading
**When** APIs are being fetched
**Then** Skeleton screens matching the metric card and alert card layouts are displayed — never blank or spinning (UX-DR12)

### Story 7.3: Implement CQRS Reporting & DLQ Management

As an admin,
I want to view business reports filtered by time range and manage failed events in the DLQ,
So that I can make data-driven decisions and ensure system reliability.

**Acceptance Criteria:**

**Given** the Reports section in Admin Dashboard
**When** I navigate to it
**Then** I see: time range selector (PrimeVue Calendar with presets: today, 7d, 30d, custom range), and charts: top selling products (bar chart by quantity and revenue), revenue by category (doughnut chart), order trends — count and status distribution (line chart) (FR49)

**Given** CQRS read models
**When** data is queried for reports
**Then** report data is served from denormalized read model views in Order Service database, synced within 30 seconds of source changes (FR49)

**Given** admin triggers "Rebuild Reports"
**When** the rebuild action is initiated
**Then** system reprocesses all order events sequentially, read model reflects current state upon completion, progress indicator shown (FR68)

**Given** the "Unprocessed Events" (DLQ) page
**When** I navigate to it
**Then** I see a DataTable with columns: Event Type, Aggregate ID, Error Reason, Timestamp, Retry Count, Actions. Rows are expandable to show full payload + stack trace (FR70)

**Given** a DLQ event row
**When** I click "Retry"
**Then** the event is reprocessed. On success: row removed, Toast "Event processed". On failure: row marked "Retry failed", Toast "Still failing — investigate" (FR70)

**Given** multiple DLQ events selected
**When** I click "Retry All"
**Then** a progress bar shows "12/15 processed", with summary on completion

**Given** API response aggregation at API Gateway
**When** admin views order detail
**Then** a single API response combines Order Service (order data) + Product Service (product info) + Payment Service (payment status) — aggregated at gateway level (FR66)

### Story 7.4: Implement System Health Monitoring

As an admin,
I want to see the health status of all microservices with key metrics,
So that I can quickly identify and respond to service issues.

**Acceptance Criteria:**

**Given** the System tab on Admin Dashboard
**When** I navigate to it
**Then** I see a grid of 7 ServiceHealthCard components — one per service (Product, Cart, Order, Inventory, Payment, Notification, API Gateway) (FR48)

**Given** ServiceHealthCard component
**When** a service is healthy
**Then** it shows: service name, green check icon, green left border, p95 response time (e.g., "45ms"). Thresholds: healthy <200ms, degraded 200-1000ms, down >1000ms (UX-DR4)

**Given** ServiceHealthCard
**When** I click to expand
**Then** expanded section shows: CPU %, Memory %, database connection pool utilization, Kafka consumer lag (FR48, UX-DR4)

**Given** KafkaLagIndicator component inside expanded ServiceHealthCard
**When** displayed
**Then** it shows: consumer group name, current lag count, mini sparkline (last 5min), status badge (Healthy <100, Elevated 100-1000, Critical >1000) (UX-DR8)

**Given** System health data
**When** WebSocket updates arrive
**Then** service status and metrics update in real-time with smooth color transitions (FR48)

**Given** Event sourcing for Order Service
**When** admin inspects order event history
**Then** the system can reconstruct entity state from event history for debugging (FR67)

---

## Epic 8: System Resilience & Graceful Degradation

System detects service failures and opens circuit breakers to prevent cascading failures. Graceful degradation across 3 tiers when services are unavailable. Rate limiting at API Gateway. Graceful shutdown for all services. Saga Phase B hardening.

### Story 8.1: Implement Circuit Breaker & Resilience Patterns

As a system,
I want circuit breakers on all inter-service calls to prevent cascading failures,
So that one failing service doesn't bring down the entire platform.

**Acceptance Criteria:**

**Given** Resilience4j configured on all services making gRPC calls
**When** a downstream service fails 5 consecutive times
**Then** the circuit breaker opens, subsequent calls fail fast with 503 without attempting the call (FR53)

**Given** an open circuit breaker
**When** the configured wait duration passes (default: 30 seconds)
**Then** the circuit transitions to half-open, allowing a test request through. If successful, circuit closes; if failed, circuit reopens

**Given** Resilience4j retry configured
**When** a transient failure occurs on a gRPC call
**Then** retry with exponential backoff is applied (3 retries, 1s initial, 2x multiplier) before circuit breaker evaluation

**Given** all services
**When** inspected for resilience configuration
**Then** @CircuitBreaker and @Retry annotations are applied on gRPC client calls, configured per-service in application.yml under resilience4j.*

### Story 8.2: Implement Graceful Degradation (3 Tiers)

As a customer,
I want the system to remain partially usable when some services are down,
So that I can still browse and manage my cart even during partial outages.

**Acceptance Criteria:**

**Given** Payment Service is down and circuit breaker is open
**When** a customer attempts checkout
**Then** order is held in PAYMENT_PENDING state, customer sees "Order received. Payment is being processed — we'll notify you when confirmed" (FR56)

**Given** Inventory Service is down
**When** a customer attempts to place an order
**Then** order placement is blocked with retry messaging: "We're experiencing a temporary issue. Please try again in a moment." (FR56)

**Given** Notification Service is down
**When** order events are produced
**Then** events queue in DLQ, orders proceed normally — notifications sent when service recovers (FR56)

**Given** Elasticsearch is down
**When** a customer searches for products
**Then** search falls back to PostgreSQL LIKE query with reduced functionality — results returned but without relevance ranking (FR56)

**Given** DegradationBanner component on Customer Website
**When** Partial degradation is detected (e.g., Payment Service down)
**Then** yellow banner below header: "Some features are temporarily limited. You can browse and add to cart — checkout will be available shortly." Dismissible per session (UX-DR6)

**Given** multiple critical services down (API Gateway or 3+ services)
**When** Maintenance tier is triggered
**Then** full-page maintenance overlay: "We're performing maintenance and will be back shortly." Not dismissible (UX-DR6)

### Story 8.3: Implement Rate Limiting & Graceful Shutdown

As a system,
I want rate limiting at the API Gateway and graceful shutdown across all services,
So that the system handles traffic spikes safely and shuts down without losing requests.

**Acceptance Criteria:**

**Given** API Gateway with RateLimitConfig
**When** an authenticated user exceeds 100 requests/minute
**Then** subsequent requests receive 429 Too Many Requests with Retry-After header (FR61)

**Given** unauthenticated clients
**When** exceeding 20 requests/minute
**Then** subsequent requests receive 429 Too Many Requests (FR61)

**Given** rate limit configuration
**When** inspected
**Then** limits are configurable per endpoint in application.yml

**Given** a K8s pod termination signal (SIGTERM)
**When** received by any service
**Then** the service executes graceful shutdown: stops accepting new requests, completes in-flight requests, commits Kafka consumer offsets, closes database connections — all within 30 seconds (FR57, NFR37)

**Given** a Kafka consumer during shutdown
**When** the service receives SIGTERM
**Then** current message batch is completed, offsets are committed, no messages are lost or reprocessed

### Story 8.4: Implement Saga Phase B — Hardened Orchestration

As a system,
I want the Saga orchestrator hardened with idempotent steps, timeouts, and dead saga detection,
So that the order flow is bulletproof under high concurrency and failure scenarios.

**Acceptance Criteria:**

**Given** Saga step execution
**When** a step is retried (e.g., after service recovery)
**Then** idempotent execution ensures the step produces the same result — no duplicate inventory reservations or payments (deduplication via saga step ID)

**Given** a Saga step
**When** it exceeds its configured timeout (e.g., 10 seconds for payment)
**Then** the step is marked as timed out and compensation is triggered

**Given** a scheduled dead saga detection job
**When** it finds sagas stuck in a non-terminal state longer than threshold (default: 5 minutes)
**Then** it triggers compensation for stuck sagas and logs the incident

**Given** 100 simultaneous order placements
**When** processed concurrently
**Then** all sagas complete or compensate correctly with no data corruption, no deadlocks, and no orphaned states (NFR6)

**Given** Saga audit log
**When** inspected after hardened execution
**Then** it records: per-step idempotency key, timeout events, dead saga detections, retry counts — full debugging context

---

## Epic 9: Observability & Operations

Developer/DevOps can trace every request end-to-end, view structured logs with correlation IDs, monitor health checks, detect data inconsistencies via reconciliation jobs, and audit all state-changing operations. This epic integrates the full observability stack.

### Story 9.1: Implement Distributed Tracing & Correlation ID Propagation

As a developer,
I want to trace any request across all services end-to-end with a single trace ID,
So that I can debug issues and understand request flow across the distributed system.

**Acceptance Criteria:**

**Given** Docker Compose
**When** updated for this story (full profile)
**Then** Grafana Tempo, Grafana Loki, Alloy (log shipper), Prometheus, and Grafana are added

**Given** OpenTelemetry configured in all services (via spring-boot-starter-opentelemetry)
**When** a request flows from API Gateway → Order Service (gRPC) → Inventory Service (gRPC) → Payment Service (gRPC)
**Then** a single trace ID is propagated through all services, visible in Grafana Tempo (FR58)

**Given** trace context propagation
**When** configured per protocol
**Then** REST is auto-instrumented, gRPC uses GrpcTracingInterceptor (from common-lib), Kafka uses TracingProducerInterceptor/TracingConsumerInterceptor (from common-lib), WebSocket has manual trace ID injection (FR58)

**Given** every API response
**When** returned to the client
**Then** traceId field is populated from the current OpenTelemetry span

**Given** CorrelationIdFilter in common-lib
**When** a request arrives without X-Correlation-Id header
**Then** one is generated and propagated through all log entries, error responses, and Kafka message headers (FR59, NFR39)

### Story 9.2: Implement Health Checks & Centralized Configuration

As a DevOps engineer,
I want health check endpoints on every service and centralized configuration management,
So that K8s can manage pod lifecycle and all services are consistently configured.

**Acceptance Criteria:**

**Given** Spring Boot Actuator on every service
**When** /actuator/health/liveness is called
**Then** it returns service process status within 1 second (FR60, NFR41)

**Given** /actuator/health/readiness on every service
**When** called
**Then** it validates: database connected, Kafka connected, Redis reachable (where applicable), custom health indicators per dependency — returns UP only when all dependencies are ready (FR60, NFR30)

**Given** Micrometer + Prometheus endpoint
**When** /actuator/prometheus is scraped
**Then** it exposes: request rate, error rate, p50/p95/p99 latency, active connections, Kafka consumer lag (NFR42)

**Given** service configuration
**When** managed via application.yml per profile (dev, demo, test)
**Then** each service is consistently configured without Config Server — K8s ConfigMaps/Secrets handle production overrides (FR62)

**Given** K8s manifests in infra/k8s/
**When** inspected
**Then** each service has: deployment.yml (with liveness/readiness probes, resource limits 256Mi/250m request, 512Mi/500m limit), service.yml, hpa.yml (NFR25, NFR29, NFR30)

### Story 9.3: Implement Service Discovery, Reconciliation & Audit Trail

As a system operator,
I want dynamic service discovery, data reconciliation, and a complete audit trail,
So that the system self-manages, detects inconsistencies, and provides accountability.

**Acceptance Criteria:**

**Given** K8s service discovery
**When** services communicate
**Then** they discover instances dynamically via K8s DNS (service-name.namespace.svc.cluster.local) — no hardcoded addresses (FR69)

**Given** scheduled reconciliation jobs
**When** running daily
**Then** they compare: inventory count vs order records, payment records vs order status — and alert admin when variance exceeds threshold (>1% discrepancy or >5 unit absolute difference) (FR71)

**Given** reconciliation results
**When** discrepancy is found
**Then** an alert is generated for admin with: affected entities, expected vs actual values, suggested resolution

**Given** any state-changing operation across all services
**When** executed
**Then** an audit trail record is created with: actor (user ID or system), action (CREATE/UPDATE/DELETE), entity type, entity ID, timestamp, trace ID (FR72, NFR18)

**Given** audit trail records
**When** queried
**Then** they are searchable by actor, action, entity, time range, and trace ID

---

## Epic 10: Comprehensive Testing & Quality Assurance

Complete testing pyramid validates all distributed patterns. CI/CD pipelines automate build, test, and deployment with quality gates. This epic builds the test-support module content and implements the full testing suite.

### Story 10.1: Implement Test Support Module & Unit Test Foundation

As a developer,
I want shared test infrastructure and comprehensive unit tests for all services,
So that business logic is verified in isolation with consistent test patterns.

**Acceptance Criteria:**

**Given** test-support module
**When** fully implemented
**Then** it provides: @IntegrationTest and @ContractTest composite annotations, PostgresContainerConfig, KafkaContainerConfig (Kafka + Schema Registry), RedisContainerConfig, ElasticsearchContainerConfig, KeycloakContainerConfig (with test realm), TestData builder (TestData.product(), TestData.order(), TestData.cartItem(), etc.), SagaTestHelper, EventAssertions (Avro deserialization + AssertJ-based content matching)

**Given** all services
**When** unit tests are written
**Then** they follow naming convention `should{Expected}When{Condition}()`, use AssertJ for all assertions, use TestData builders for test data (never `new Entity()` + setters), mock dependencies with Mockito (NFR52, NFR60)

**Given** unit test coverage
**When** measured per service
**Then** minimum 80% line coverage is achieved (NFR52)

**Given** Testcontainers configuration
**When** used across services
**Then** singleton containers are shared across all tests in a service, `testcontainers.reuse.enable=true` is configured in .testcontainers.properties (NFR53)

### Story 10.2: Implement Integration & Contract Tests

As a developer,
I want integration tests with real infrastructure and contract tests validating all service interfaces,
So that I can prove services work correctly together and contracts are not broken.

**Acceptance Criteria:**

**Given** integration tests per service
**When** annotated with @IntegrationTest
**Then** they run with Testcontainers (real PostgreSQL, Kafka, Redis, Elasticsearch, Keycloak as needed) — no dependency on external environments (NFR53)

**Given** Product Service integration tests
**When** testing ProductRestControllerIT
**Then** full request/response flow is validated including Flyway migrations, seed data, Elasticsearch sync, and response format

**Given** Order Service integration tests
**When** testing Saga flow
**Then** SagaTestHelper sets up: create order in specific state, simulate step failure, verify compensation executes correctly (NFR43)

**Given** REST API contract tests
**When** Pact consumer-driven tests run
**Then** consumer expectations are verified against provider implementations for all REST service pairs (NFR54, NFR62)

**Given** gRPC contract tests
**When** Protobuf schema validation runs with buf lint
**Then** all .proto files pass backward-compatibility checks — no removed/renamed fields (NFR48, NFR62)

**Given** Kafka event contract tests
**When** Avro schema compatibility is checked via Schema Registry
**Then** all event schemas pass backward-compatible evolution rules (NFR58, NFR62)

**Given** failed test reports
**When** generated
**Then** they include: full request/response payloads, service logs, and trace IDs for reproducibility (NFR59)

### Story 10.3: Implement E2E, Performance & Chaos Tests

As a developer,
I want end-to-end tests, performance tests, and chaos tests,
So that I can prove the entire system works correctly under load and failure conditions.

**Acceptance Criteria:**

**Given** E2E tests
**When** executing full order flow
**Then** the test covers: product search → add to cart → login → checkout → place order → payment → order confirmation → notification sent — all services involved

**Given** k6 performance tests
**When** simulating 100 concurrent order placements
**Then** no data corruption, no overselling, Saga completion within 3 seconds (NFR3, NFR6)

**Given** k6 flash sale simulation
**When** 100 users compete for 1 item simultaneously
**Then** exactly 1 order succeeds, 99 receive "Out of Stock", no duplicate charges (NFR6)

**Given** chaos tests
**When** individual services are killed during operation
**Then** system recovers to healthy state within 60 seconds — Circuit Breaker opens, DLQ captures failed events, services restart via K8s liveness probes (NFR34, NFR61)

**Given** chaos tests with network latency injection
**When** 500ms latency is added to inter-service gRPC calls
**Then** system continues operating with degraded performance, no timeouts or data loss

### Story 10.4: Implement CI/CD Pipelines & Quality Gates

As a developer,
I want automated CI/CD pipelines that build, test, and deploy with strict quality gates,
So that only verified code reaches production.

**Acceptance Criteria:**

**Given** ci-backend.yml GitHub Actions workflow
**When** triggered on push/PR
**Then** it executes: Maven build (parallel per module), unit tests, integration tests (Testcontainers), contract tests (Pact + Protobuf + Schema Registry), ArchUnit validation, Checkstyle, OpenAPI drift detection — pipeline blocks on any failure (NFR49, NFR55)

**Given** ci-frontend.yml GitHub Actions workflow
**When** triggered on push/PR
**Then** it executes: npm build, Vitest tests, ESLint + Prettier check, eslint-plugin-vuejs-accessibility check, axe-core accessibility audit

**Given** schema-compatibility.yml workflow
**When** triggered on changes to proto/ or events/
**Then** buf lint validates Protobuf backward compatibility, Schema Registry validates Avro backward compatibility (NFR48, NFR58)

**Given** cd-deploy.yml workflow
**When** triggered on main branch merge
**Then** it executes: Docker multi-stage builds per service, push to ghcr.io, Helm deploy to K8s with rolling update strategy (zero-downtime) (NFR50, NFR51)

**Given** full CI/CD pipeline
**When** measured end-to-end
**Then** build + test + deploy completes in under 15 minutes (NFR49)

**Given** test suite per service
**When** measured
**Then** unit + integration + contract tests complete within 10 minutes (NFR56)
