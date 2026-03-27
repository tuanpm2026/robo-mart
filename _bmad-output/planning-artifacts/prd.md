---
stepsCompleted:
  - step-01-init
  - step-02-discovery
  - step-02b-vision
  - step-02c-executive-summary
  - step-03-success
  - step-04-journeys
  - step-05-domain
  - step-06-innovation
  - step-07-project-type
  - step-08-scoping
  - step-09-functional
  - step-10-nonfunctional
  - step-11-polish
  - step-12-complete
inputDocuments:
  - ecommerce_system_spec.md
workflowType: 'prd'
documentCounts:
  briefs: 0
  research: 0
  brainstorming: 0
  projectDocs: 0
  specFiles: 1
classification:
  projectType: Distributed Microservices Platform (multi-frontend)
  domain: E-commerce
  complexity: Medium-High
  projectContext: greenfield
  primaryGoal: Technical Learning & Portfolio
  subDomains:
    - Catalog Management
    - Order Management
    - Payment Processing
    - Customer Experience
---

# Product Requirements Document - robo-mart

**Author:** Mark
**Date:** 2026-03-26

## Executive Summary

RoboMart is a production-grade Ecommerce platform designed as a comprehensive technical showcase for Senior Java Engineering competency. Built on a Distributed Microservices Architecture with 7 core services (API Gateway, Product, Cart, Order, Inventory, Payment, Notification), the system deliberately surfaces and solves real-world distributed system challenges — from Saga-based transaction coordination to eventual consistency via Kafka event streams.

The platform serves two frontend applications: a **Customer Website** for product discovery, cart management, ordering, and payment; and an **Admin Dashboard** for product catalog management, inventory control, order tracking, and reporting. The backend leverages Java Spring Boot, Kafka, Redis, Elasticsearch, and PostgreSQL — each chosen to address specific distributed system patterns.

**Primary Goal:** Every architectural decision and feature implementation maps directly to Senior Java interview topics. The system is not a toy demo — it is built to production standards with real distributed challenges, making every feature a concrete, demonstrable answer to technical interview questions.

### What Makes This Special

Unlike typical learning projects that implement patterns in isolation, RoboMart creates an integrated system where distributed challenges emerge **naturally from business requirements**:

- Inventory oversell prevention requires **Distributed Locking** and **Race Condition** handling
- Cross-service order flow demands the **Saga Pattern** with compensating transactions
- Kafka-based event streaming introduces **Eventual Consistency**, **Outbox Pattern**, and **Dead Letter Queues**
- Payment processing (mock) exercises **Idempotency** and **Retry Mechanisms**
- System resilience requires **Circuit Breaker**, **Graceful Degradation**, and **Backpressure**
- Quality assurance demands **Contract Testing**, **Testcontainers**, and **Chaos Testing** fundamentals

Patterns are prioritized into two tiers: **Core** (Saga, Idempotency, Eventual Consistency, Circuit Breaker, Distributed Locking, Outbox Pattern, DLQ) for immediate implementation, and **Advanced** (CQRS, Event Sourcing, Distributed Tracing, Rate Limiting, API Versioning) for iterative enhancement.

## Project Classification

- **Project Type:** Distributed Microservices Platform (multi-frontend)
- **Domain:** E-commerce
- **Complexity:** Medium-High — 7 microservices, event-driven architecture, distributed state management, 2 frontend applications
- **Project Context:** Greenfield
- **Primary Goal:** Technical Learning & Portfolio — Senior Java Engineer interview preparation
- **Sub-domains:** Catalog Management, Order Management, Payment Processing, Customer Experience

## Success Criteria

### User Success (Developer as User)

- Confidently answer common-to-hard Senior Java interview questions about distributed systems with real implementation experience
- Explain every distributed pattern (Saga, Circuit Breaker, Outbox, DLQ, Distributed Locking, Idempotency, Eventual Consistency, CQRS, Event Sourcing...) by referencing actual code and design decisions in this project
- Demonstrate end-to-end understanding: from business requirement → architectural decision → implementation → testing → deployment

### Technical Success

- All 7 microservices running reliably on Kubernetes (K8s)
- All business features fully functional: product search, cart, ordering, inventory management, payment (mock), notifications
- All distributed system challenges solved and verifiable:
  - No inventory oversell under concurrent requests (Distributed Locking)
  - Order flow completes or compensates correctly across services (Saga Pattern)
  - No duplicate payments or duplicate order processing (Idempotency)
  - System recovers gracefully when a service goes down (Circuit Breaker, Graceful Degradation)
  - Events are never lost between DB writes and Kafka publishes (Outbox Pattern)
  - Failed messages are captured and recoverable (Dead Letter Queue)
  - Search index stays consistent with product data (Eventual Consistency)
  - System handles traffic spikes without cascading failures (Rate Limiting, Backpressure)
- Full observability: Distributed Tracing across all service calls
- CI/CD pipeline: build, test, dockerize, deploy to K8s — fully automated

### Testing Success

- **Unit Tests:** Comprehensive coverage for every service's business logic
- **Integration Tests:** Testcontainers-based tests with real Kafka, Redis, PostgreSQL, Elasticsearch
- **Contract Tests:** Pact-based consumer-driven contract testing between all service pairs
- **E2E Tests:** Full user flow testing — product search → add to cart → place order → payment → notification
- **Performance Tests:** k6 load testing proving system handles concurrent orders without data corruption
- **Chaos Testing:** Kill individual services, verify system recovers and no data is lost
- Zero tolerance: all tests must pass before deployment

### Measurable Outcomes

- Pass mock Senior Java interview covering: microservices architecture, distributed consistency, event-driven systems, testing strategies, deployment & observability
- All distributed patterns implemented with working code, not just theory
- System runs stable on K8s cluster with no manual intervention needed

## Product Scope

### Full Scope (No MVP — Complete Implementation)

**Backend Microservices:**
- API Gateway — routing, rate limiting, authentication
- Product Service — CRUD, Elasticsearch sync, search
- Cart Service — Redis-based, session management
- Order Service — state machine, Saga orchestration
- Inventory Service — distributed locking, stock management
- Payment Service — mock provider, idempotency, retry
- Notification Service — event-driven, email/push notifications

**Distributed System Patterns (All Required):**
- Core: Saga, Idempotency, Eventual Consistency, Circuit Breaker, Distributed Locking, Outbox Pattern, DLQ
- Advanced: CQRS, Event Sourcing, Distributed Tracing, Rate Limiting, API Versioning, Backpressure, Graceful Degradation, Service Discovery, Load Balancing, Database per Service

**Frontend Applications:**
- Customer Website — product browsing, search, cart, checkout, order tracking
- Admin Dashboard — product management, inventory, orders, reporting

**Testing Suite:**
- Unit, Integration (Testcontainers), Contract (Pact), E2E, Performance (k6), Chaos

**Infrastructure & DevOps:**
- Docker containerization for all services
- Kubernetes (K8s) deployment
- CI/CD pipeline via GitHub Actions
- Full observability stack (distributed tracing)

## User Journeys

### Journey 1: Customer — Happy Path (Search → Purchase → Receive Notification)

**Persona:** Linh, 28, office worker, thích mua sắm online vào buổi tối sau giờ làm.

**Opening Scene:** Linh cần mua tai nghe mới. Cô mở RoboMart trên browser, gõ "wireless headphone" vào thanh tìm kiếm.

**Rising Action:**
1. **Search** — Elasticsearch trả về kết quả nhanh, có filter theo giá, brand, rating. Linh thấy 3 sản phẩm phù hợp.
2. **Product Detail** — Linh click vào sản phẩm, xem mô tả, specs, giá, tình trạng kho ("In Stock — 5 left").
3. **Add to Cart** — Linh thêm vào giỏ hàng. Cart được lưu trên Redis, nên khi Linh mở tab khác rồi quay lại, giỏ hàng vẫn còn.
4. **Checkout** — Linh nhập địa chỉ giao hàng, chọn phương thức thanh toán. Hệ thống tạo Order ở trạng thái PENDING.
5. **Payment** — Payment Service xử lý (mock), gửi idempotency key để đảm bảo không charge 2 lần. Payment thành công.
6. **Order Confirmation** — Order chuyển sang CONFIRMED. Inventory Service trừ stock. Kafka event được publish.
7. **Notification** — Notification Service consume event, gửi email xác nhận đơn hàng cho Linh.

**Climax:** Linh nhận email xác nhận trong vài giây — đơn hàng, chi tiết sản phẩm, estimated delivery — mọi thứ rõ ràng, chuyên nghiệp.

**Resolution:** Linh vào trang "My Orders" để theo dõi trạng thái đơn hàng. Trải nghiệm mượt mà từ đầu đến cuối.

**Distributed Patterns Exercised:** Elasticsearch sync (Eventual Consistency), Redis cart (Session Management), Saga (Order→Inventory→Payment), Idempotency (Payment), Kafka events (Event-Driven), Outbox Pattern (DB→Kafka guarantee).

---

### Journey 2: Customer — Edge Case (Concurrent Purchase & Out of Stock)

**Persona:** Minh, 35, developer, cùng nhắm một sản phẩm flash sale chỉ còn 1 item.

**Opening Scene:** Flash sale bắt đầu lúc 12:00. Minh và 50 người khác cùng click "Buy Now" gần như đồng thời cho sản phẩm chỉ còn 1 unit trong kho.

**Rising Action:**
1. **Concurrent Requests** — 50 requests đặt hàng đổ vào Order Service cùng lúc.
2. **Distributed Locking** — Inventory Service sử dụng Redis distributed lock. Chỉ 1 request acquire lock thành công và trừ stock.
3. **Minh thắng** — Request của Minh acquire lock đầu tiên, stock giảm từ 1 → 0. Order chuyển sang CONFIRMED.
4. **49 người còn lại** — Nhận response "Out of Stock". Order không được tạo hoặc bị cancel ngay lập tức.
5. **Payment đã charge nhưng inventory fail?** — Saga compensating transaction: Payment Service refund tự động.

**Climax:** Không có oversell. Không có 2 người cùng mua 1 item cuối cùng. Hệ thống handle 50 concurrent requests sạch sẽ.

**Resolution:** Minh nhận confirmation. 49 người còn lại nhận thông báo "Sold Out" với gợi ý sản phẩm tương tự. Không ai bị charge sai.

**Distributed Patterns Exercised:** Distributed Locking (Race Condition), Saga (Compensating Transaction), Circuit Breaker (under load), Rate Limiting (API Gateway), Backpressure (Kafka consumer).

---

### Journey 3: Customer — Service Failure Recovery

**Persona:** Hà, 42, business owner, đang đặt hàng bulk 20 items cho công ty.

**Opening Scene:** Hà đã add 20 items vào cart, nhập thông tin thanh toán, và click "Place Order". Đúng lúc đó, Payment Service bị crash.

**Rising Action:**
1. **Order Created** — Order Service tạo order PENDING thành công, publish event lên Kafka.
2. **Payment Service Down** — Circuit Breaker detect Payment Service không phản hồi. Sau 3 retry (với exponential backoff), circuit opens.
3. **Graceful Degradation** — Thay vì trả 500 error, hệ thống giữ order ở trạng thái PAYMENT_PENDING. Hà nhận thông báo: "Đơn hàng đã được ghi nhận. Thanh toán đang xử lý, chúng tôi sẽ thông báo khi hoàn tất."
4. **DLQ** — Payment event vào Dead Letter Queue để xử lý khi service recovery.
5. **Recovery** — Payment Service khởi động lại. DLQ consumer replay failed messages. Payment processed thành công.
6. **Notification** — Hà nhận email "Thanh toán thành công, đơn hàng đã xác nhận."

**Climax:** Hà không mất đơn hàng, không bị charge trùng, và không cần đặt lại. Hệ thống tự phục hồi.

**Resolution:** Toàn bộ 20 items được xử lý đúng. Inventory đã trừ chính xác. Hà tin tưởng hệ thống.

**Distributed Patterns Exercised:** Circuit Breaker, Retry (Exponential Backoff), DLQ, Graceful Degradation, Idempotency (no duplicate charge), Saga (state management during failure).

---

### Journey 4: Admin — Product & Inventory Management

**Persona:** Tuấn, 30, operations manager, quản lý catalog sản phẩm và kho hàng hàng ngày.

**Opening Scene:** Tuấn đăng nhập Admin Dashboard vào buổi sáng để cập nhật catalog và kiểm tra tình hình kho.

**Rising Action:**
1. **Dashboard Overview** — Tuấn thấy tổng quan: orders hôm nay, low-stock alerts, revenue summary.
2. **Add New Product** — Tuấn tạo sản phẩm mới với tên, mô tả, giá, hình ảnh, category. Product Service lưu vào PostgreSQL. Outbox Pattern đảm bảo event publish lên Kafka. Elasticsearch index tự động cập nhật.
3. **Inventory Update** — Tuấn nhập thêm 500 units cho sản phẩm bán chạy. Inventory Service cập nhật stock, publish event.
4. **Order Management** — Tuấn xem danh sách orders, filter theo status (PENDING, CONFIRMED, SHIPPED, DELIVERED). Xem chi tiết từng order.
5. **Low Stock Alert** — Hệ thống tự động alert khi stock < threshold. Tuấn thấy 3 sản phẩm cần restock.
6. **Reporting** — Tuấn xem báo cáo: top selling products, revenue by category, order trends.

**Climax:** Tuấn quản lý toàn bộ operations từ một dashboard, dữ liệu real-time, không cần check nhiều hệ thống.

**Resolution:** Sau 30 phút, Tuấn đã cập nhật catalog, restock inventory, và review toàn bộ orders. Mọi thay đổi sync ngay lập tức qua Kafka events đến Customer Website.

**Distributed Patterns Exercised:** Outbox Pattern (Product→Elasticsearch sync), Eventual Consistency (admin changes → customer-facing data), Event-Driven (Kafka events across services), CQRS (read model vs write model for reporting).

---

### Journey 5: System — Inter-Service Communication & Observability

**Persona:** DevOps/Developer monitoring hệ thống production.

**Opening Scene:** Một order đang stuck ở trạng thái PAYMENT_PENDING quá lâu. Developer cần trace toàn bộ flow để tìm nguyên nhân.

**Rising Action:**
1. **Distributed Tracing** — Developer mở tracing dashboard, search bằng order ID. Thấy toàn bộ call chain: API Gateway → Order Service → Inventory Service → Payment Service → Notification Service.
2. **Identify Bottleneck** — Trace cho thấy Payment Service response time tăng đột biến từ 200ms lên 5000ms.
3. **Service Health Check** — Kiểm tra Payment Service metrics: CPU 95%, memory 85%, connection pool exhausted.
4. **Root Cause** — Kafka consumer lag cao, messages đang pile up. Consumer không scale kịp.
5. **Resolution** — Scale Payment Service instances, Kafka consumer group tự rebalance partitions. Backlog được xử lý.
6. **Verification** — Stuck orders chuyển sang CONFIRMED. DLQ empty. System healthy.

**Climax:** Từ phát hiện vấn đề đến giải quyết, developer trace được toàn bộ flow qua distributed tracing mà không cần đoán mò.

**Resolution:** Hệ thống tự phục hồi, thêm alert rule cho consumer lag để phát hiện sớm lần sau.

**Distributed Patterns Exercised:** Distributed Tracing, Service Discovery, Load Balancing, Backpressure, Consumer Group Rebalancing, Health Check, Observability.

---

### Journey 6: Customer — Registration & Authentication Flow

**Persona:** Lan, 25, sinh viên, lần đầu sử dụng RoboMart.

**Opening Scene:** Lan thấy sản phẩm hay, click "Add to Cart" nhưng hệ thống yêu cầu đăng nhập.

**Rising Action:**
1. **Registration Options** — Lan thấy 3 lựa chọn: email/password, Google login, GitHub login. Lan chọn Google login cho nhanh.
2. **OAuth2 Flow** — Keycloak redirect sang Google consent screen. Lan authorize. Google trả về user info.
3. **Account Created** — Keycloak tạo account tự động, map Google profile vào user record. JWT issued.
4. **Seamless Return** — Lan được redirect lại trang sản phẩm, cart vẫn giữ item vừa thêm (anonymous cart merge).
5. **Profile Update** — Lan vào profile page, thêm shipping address, phone number.
6. **Session Expiry** — 15 phút sau, JWT expire. System tự dùng refresh token lấy JWT mới — Lan không bị logout.

**Climax:** Trải nghiệm đăng ký mượt mà — 2 clicks với social login, không cần nhớ thêm password mới.

**Resolution:** Lần sau Lan quay lại, click "Login with Google" → vào ngay, cart cũ vẫn còn trong Redis.

**Distributed Patterns Exercised:** OAuth2/OIDC (Keycloak), JWT lifecycle (access + refresh), Session Management (Redis cart merge), API Gateway auth validation.

---

### Journey Requirements Summary

| Journey | Capabilities Required |
|---------|----------------------|
| **Customer Happy Path** | Product Search, Cart Management, Order Processing, Payment, Notification, Order Tracking |
| **Concurrent Purchase** | Distributed Locking, Race Condition Handling, Saga Compensation, Rate Limiting |
| **Service Failure** | Circuit Breaker, Retry, DLQ, Graceful Degradation, Idempotency |
| **Admin Management** | Product CRUD, Inventory Management, Order Management, Reporting, Real-time Sync |
| **System Observability** | Distributed Tracing, Health Checks, Metrics, Alerting, Auto-scaling |
| **Registration & Auth** | OAuth2/OIDC, Social Login, JWT Lifecycle, Session Management, Cart Merge |

## Distributed Microservices Platform Specific Requirements

### Project-Type Overview

RoboMart is a hybrid platform combining a microservices backend (Java Spring Boot) with two SPA frontends (Vue.js). The system prioritizes backend engineering depth over frontend polish — frontends serve as functional interfaces to exercise and demonstrate backend capabilities.

### Frontend Architecture

**Technology:** Vue.js (SPA) for both applications

| Application | Purpose | Key Features |
|-------------|---------|--------------|
| **Customer Website** | Product browsing & purchasing | Search, cart, checkout, order tracking |
| **Admin Dashboard** | Operations management | Product CRUD, inventory, orders, reporting, real-time monitoring |

**Frontend Considerations:**
- No SSR/SSG required — SEO is not a concern
- Both apps consume backend REST APIs (and partial GraphQL)
- Admin Dashboard includes **WebSocket** connection for real-time monitoring (order events, inventory alerts, system health)
- Frontend complexity kept minimal — focus is on backend integration patterns

### Authentication & Authorization

**Identity Provider:** Keycloak (OAuth2 / OpenID Connect)

- **Single Sign-On** across both Customer Website and Admin Dashboard
- **Social Login** (Google, GitHub) for customer registration — practical OAuth2 integration exercise
- **Role-Based Access Control (RBAC):**
  - Customer role: browse, purchase, view own orders
  - Admin role: full CRUD on products, inventory, orders, view reports
- **Token-based auth:** JWT access tokens + refresh tokens
- **API Gateway** validates JWT on every request — no service-level auth duplication
- Keycloak runs as a dedicated infrastructure service alongside Kafka, Redis, PostgreSQL, Elasticsearch

### Communication Architecture (Hybrid Multi-Protocol)

| Channel | Protocol | Use Case |
|---------|----------|----------|
| Frontend → API Gateway | **REST** + **GraphQL** | Browser compatibility, flexible queries |
| API Gateway → Services | **gRPC** | Low latency internal routing |
| Service ↔ Service (sync) | **gRPC** | Direct service calls (Order→Inventory, Order→Payment) |
| Service ↔ Service (async) | **Kafka** | Event-driven flows (order events, notifications, search sync) |
| Admin Dashboard | **WebSocket** (STOMP) | Real-time monitoring |

**REST (External-facing):**
- Standard REST conventions: proper HTTP methods, status codes, error responses
- API Versioning via URL path (`/api/v1/`, `/api/v2/`)
- OpenAPI/Swagger documentation for all endpoints
- Consistent error response format across services: `{code, message, traceId, timestamp}`

**GraphQL (Product Service):**
- GraphQL endpoint for product queries — flexible filtering, nested data (product + reviews + inventory status)
- Allows comparison between REST vs GraphQL approaches for same data
- Learning focus: schema design, resolvers, N+1 problem, DataLoader pattern

**gRPC (Internal Service-to-Service):**
- Protocol Buffers (binary serialization) for high-performance inter-service communication
- `.proto` files as shared contracts between services — type-safe, compile-time validation
- Protobuf schema evolution — backward-compatible versioning
- HTTP/2 multiplexing for efficient connection usage
- API Gateway translates REST → gRPC for internal routing

**WebSocket (Real-time):**
- Spring WebSocket + STOMP protocol for Admin Dashboard
- Live order event stream, inventory alerts, system health metrics

### Centralized Configuration

- **Spring Cloud Config Server** or **Kubernetes ConfigMaps/Secrets** for centralized configuration management
- All service configs (DB URLs, Kafka brokers, Redis hosts, Keycloak realm) managed centrally
- Environment-specific profiles (dev, staging, production)
- Runtime config refresh without service restart

### Observability & Operations

**Logging:**
- Structured logging (JSON format) across all services
- **Correlation ID** propagated through entire request chain (REST → gRPC → Kafka)
- Centralized log aggregation

**Health Checks:**
- **Liveness probes** — service process alive (Spring Boot Actuator `/health/liveness`)
- **Readiness probes** — service ready for traffic: DB connected, Kafka connected, Redis reachable (`/health/readiness`)
- Custom health indicators for each dependency

**Distributed Tracing:**
- Full request tracing across all service calls (REST, gRPC, Kafka)
- Trace ID visible in error responses and logs

### Error Handling Convention

- Global exception handler via `@ControllerAdvice` in every service
- Consistent error response format: `{code, message, traceId, timestamp}`
- gRPC status codes mapped to REST-equivalent responses at API Gateway

### Database Migration

- **Flyway** for schema versioning and migration across all PostgreSQL databases
- Each service manages its own migrations independently (Database per Service)
- Migration scripts version-controlled alongside service code

### Data Stores (Database per Service)

| Service | Primary Store | Cache/Secondary |
|---------|--------------|-----------------|
| Product Service | PostgreSQL | Elasticsearch (search index) |
| Cart Service | Redis | — |
| Order Service | PostgreSQL | — |
| Inventory Service | PostgreSQL | Redis (distributed lock) |
| Payment Service | PostgreSQL | — |
| Notification Service | PostgreSQL | — |
| Keycloak | PostgreSQL | — |

### Infrastructure Services

- Kafka (event streaming) + KRaft (no Zookeeper)
- Redis (cart storage, distributed locking, caching)
- Elasticsearch (product search)
- Keycloak (identity & access management)
- API Gateway (Spring Cloud Gateway)
- Config Server (centralized configuration)

### Implementation Considerations

- Frontend (Vue.js) kept intentionally lean — functional UI, not pixel-perfect design
- Backend services are the primary learning focus — every service exercises specific distributed patterns
- gRPC for internal calls provides interview-ready comparison: "When to use gRPC vs REST vs GraphQL?"
- WebSocket on Admin Dashboard demonstrates real-time event consumption from Kafka
- Keycloak adds production-realistic auth with social login learning opportunity
- All services must be independently deployable with their own database
- Flyway migrations ensure reproducible schema across environments

## Project Scoping & Phased Development

### Development Philosophy

No MVP — complete implementation of all features, patterns, and testing. Phases represent **build order based on technical dependencies**, not scope reduction. Every phase builds upon the previous one.

### Phase 1: Foundation (Infrastructure + Core Services)

**Goal:** Establish shared infrastructure and first working vertical slice.

**Infrastructure Setup:**
- Kubernetes (K8s) cluster configuration
- Kafka + KRaft deployment
- Redis deployment
- PostgreSQL instances (per service)
- Elasticsearch deployment
- Keycloak setup + realm configuration
- Spring Cloud Config Server
- CI/CD pipeline (GitHub Actions)
- API Gateway (Spring Cloud Gateway) with basic routing

**First Services:**
- **Product Service** — CRUD + Elasticsearch sync (Outbox Pattern) + Flyway migrations
- **Cart Service** — Redis-based cart management
- gRPC contracts (`.proto` files) for Product and Cart

**Frontend:**
- Customer Website (Vue.js) — product browsing + search + cart
- Keycloak integration (login, social login, JWT)

**Patterns Exercised:** Database per Service, Outbox Pattern, Eventual Consistency (PostgreSQL→Elasticsearch), Centralized Config, API Versioning, gRPC basics

### Phase 2: Order Flow (Distributed Transactions)

**Goal:** Implement the most complex distributed patterns — order processing with Saga.

**Services:**
- **Order Service** — state machine, Saga orchestrator
- **Inventory Service** — stock management, distributed locking (Redis)
- **Payment Service** — mock provider, idempotency, retry with exponential backoff

**Patterns Exercised:** Saga Pattern (orchestration), Distributed Locking (Redis), Race Condition handling, Idempotency, Retry Mechanism, Circuit Breaker, Graceful Degradation, DLQ

**Frontend:**
- Customer Website — checkout flow, order tracking, payment
- Admin Dashboard (Vue.js) — basic product & inventory management

### Phase 3: Event-Driven & Real-Time

**Goal:** Complete async communication layer and real-time features.

**Services:**
- **Notification Service** — event-driven email/push notifications
- Kafka event flows fully connected across all services

**Features:**
- WebSocket for Admin Dashboard (live order stream, inventory alerts, system health)
- Admin reporting (CQRS read models)
- GraphQL endpoint on Product Service

**Patterns Exercised:** Event-Driven Architecture, CQRS, Event Sourcing, WebSocket, GraphQL, Backpressure, Consumer Group Rebalancing

### Phase 4: Resilience, Observability & Advanced Patterns

**Goal:** Production-grade hardening and advanced patterns.

**Features:**
- Distributed Tracing across all protocols (REST, gRPC, Kafka)
- Structured logging + Correlation ID propagation
- Health checks (Liveness/Readiness probes) + Spring Boot Actuator
- Rate Limiting at API Gateway
- API Versioning (v1/v2 demonstration)
- Service Discovery + Load Balancing
- Graceful Degradation scenarios

**Patterns Exercised:** Distributed Tracing, Rate Limiting, Backpressure, Service Discovery, Load Balancing, Observability

### Phase 5: Testing & Quality Assurance

**Goal:** Comprehensive testing pyramid — prove every pattern works.

**Testing:**
- Unit Tests for all services
- Integration Tests with Testcontainers (Kafka, Redis, PostgreSQL, Elasticsearch, Keycloak)
- Contract Tests (Pact for REST/gRPC, Protobuf schema validation)
- E2E Tests (full order flow)
- Performance Tests (k6 — concurrent orders, flash sale simulation)
- Chaos Tests (kill services, verify recovery)
- API Gateway routing and filter chain tests

### Risk Mitigation Strategy

**Technical Risks:**
- gRPC + REST + GraphQL + Kafka + WebSocket = 5 communication protocols — risk of complexity overload. Mitigation: build one protocol per phase, not all at once.
- Keycloak adds significant infrastructure complexity. Mitigation: set up early in Phase 1, use for all subsequent phases.

**Resource Risks (Solo Developer, 4hrs/day):**
- Phase 1-2 are heaviest (infrastructure + Saga). If pace slows, Phase 3-5 features can be added incrementally without blocking core functionality.
- Each phase delivers a working, testable increment.

**Learning Risks:**
- Attempting too many new technologies simultaneously. Mitigation: each phase introduces 2-3 new patterns max, allowing time to learn deeply before moving on.

## Functional Requirements

### Product Discovery & Search

- FR1: Customer can browse products by category
- FR2: Customer can search products by keyword with full-text search
- FR3: Customer can filter search results by price, brand, rating, and category
- FR4: Customer can view product detail including description, specifications, price, images, and stock availability
- FR5: System can sync product data from PostgreSQL to Elasticsearch within 30 seconds of data change (aligned with NFR32 eventual consistency window)
- FR6: Customer can query products via GraphQL endpoint with flexible filtering and nested data

### Cart Management

- FR7: Customer can add products to cart
- FR8: Customer can update product quantity in cart
- FR9: Customer can remove products from cart
- FR10: Customer can view cart summary with total price
- FR11: System can persist cart data across browser sessions for authenticated users
- FR12: System can expire cart after configurable TTL (default 24 hours) and release held references
- FR13: System can send email notification to customer 2 hours before cart expiration with cart contents summary and direct checkout link

### Order Processing

- FR14: Customer can place an order from cart contents
- FR15: System can coordinate order creation across services using Saga pattern (Order → Inventory → Payment)
- FR16: System can execute compensating transactions when any step in the order flow fails
- FR17: Customer can view order history and order details
- FR18: Customer can track order status changes (PENDING → CONFIRMED → SHIPPED → DELIVERED)
- FR19: System can manage order state transitions via state machine
- FR20: Customer can cancel an order in PENDING or CONFIRMED status
- FR21: System can execute Saga compensation on order cancellation (refund payment → release inventory → send cancellation notification)
- FR22: System can resolve race condition between order cancellation and in-flight payment — if payment completes before cancellation is processed, system issues automatic refund; if cancellation is processed first, payment request is rejected

### Inventory Management

- FR23: System can reserve inventory atomically under concurrent requests without overselling
- FR24: Admin can view current stock levels for all products
- FR25: Admin can update stock quantities (restock)
- FR26: System can generate low-stock alerts when inventory falls below configurable threshold (default: 10 units per product)
- FR27: System can release reserved inventory when order is cancelled or payment fails

### Payment Processing

- FR28: System can process payments via mock payment provider
- FR29: System can guarantee payment idempotency (no duplicate charges)
- FR30: System can retry failed payments with exponential backoff (max 3 retries, initial delay 1 second, backoff multiplier 2x)
- FR31: System can refund payments automatically when order compensation is triggered
- FR32: System can handle payment service unavailability gracefully (order remains in PAYMENT_PENDING)

### Notification

- FR33: System can send order confirmation notification to customer after successful purchase
- FR34: System can send payment success/failure notification to customer
- FR35: System can send low-stock alerts to admin
- FR36: System can deliver notifications via event-driven consumption from Kafka

### User Identity & Access

- FR37: Customer can register and log in via Keycloak (email/password)
- FR38: Customer can register and log in via social login (Google, GitHub)
- FR39: Admin can log in via Keycloak with admin role
- FR40: System can enforce role-based access control (Customer vs Admin permissions)
- FR41: System can validate JWT tokens at API Gateway for all protected endpoints
- FR42: System can manage token refresh without requiring re-login

### Admin Dashboard Operations

- FR43: Admin can create, read, update, and delete products
- FR44: Admin can view and manage all orders with filtering by status
- FR45: Admin can view order details including items, customer info, payment status
- FR46: Admin can view real-time order events via WebSocket
- FR47: Admin can view real-time inventory alerts via WebSocket
- FR48: Admin can view system health status via WebSocket — service up/down state, CPU/memory usage, Kafka consumer lag, database connection pool utilization
- FR49: Admin can view reports filtered by time range (today, last 7 days, last 30 days, custom date range): top selling products (by quantity and revenue), revenue by category, order trends (count and status distribution) — powered by CQRS read models with data sync within 30 seconds

### System Resilience & Communication

- FR50: System can route all external requests through API Gateway
- FR51: System can communicate between services via gRPC for synchronous calls
- FR52: System can communicate between services via Kafka for asynchronous events
- FR53: System can detect service failures and open circuit breaker to prevent cascading failures
- FR54: System can queue failed events in Dead Letter Queue for later reprocessing
- FR55: System can guarantee event delivery from database to Kafka via Outbox Pattern
- FR56: System can degrade gracefully when dependent services are unavailable — Payment Service down: orders held in PAYMENT_PENDING; Inventory Service down: order placement blocked with retry; Notification Service down: events queued in DLQ, orders proceed normally; Elasticsearch down: product search falls back to PostgreSQL query
- FR57: System can execute graceful shutdown on all services: stop accepting new requests, complete in-flight requests, commit Kafka consumer offsets, and close database connections before termination

### Observability & Operations

- FR58: System can trace requests across all services with distributed tracing (REST, gRPC, Kafka)
- FR59: System can propagate correlation ID through entire request chain
- FR60: System can expose health check endpoints (liveness and readiness) for every service
- FR61: System can apply rate limiting at API Gateway — default 100 requests/minute per authenticated user, 20 requests/minute for unauthenticated clients, configurable per endpoint
- FR62: System can manage centralized configuration for all services
- FR63: System can execute database schema migrations automatically via Flyway

### Cross-Cutting Capabilities

- FR64: System can cache frequently accessed data (product details, search results) in Redis with configurable TTL per data type (default: 5 minutes for product details, 1 minute for search results)
- FR65: System can invalidate cached data automatically when source data changes via Kafka event-driven cache invalidation
- FR66: System can aggregate data from multiple services into a single API response at the gateway level — e.g., order detail combines Order Service (order data) + Product Service (product info) + Payment Service (payment status) into one response
- FR67: System can reconstruct entity state from event history for Order Service
- FR68: System can replay events from event store to rebuild CQRS read models on demand — admin triggers rebuild, system reprocesses all events sequentially, read model reflects current state upon completion
- FR69: System can discover service instances dynamically without hardcoded addresses
- FR70: Admin can view Dead Letter Queue messages and trigger manual reprocessing
- FR71: System can detect data inconsistencies between services via scheduled reconciliation jobs (inventory count vs order records, payment records vs order status) and alert admin when variance exceeds threshold (default: >1% discrepancy or >5 unit absolute difference)
- FR72: System can maintain audit trail of all state-changing operations across services with fields defined in NFR18 (actor, action, timestamp, trace ID)
- FR73: System can paginate and sort results for all list-based queries (products, orders, search results)
- FR74: Admin can upload and manage product images — supported formats: JPEG, PNG, WebP; max file size: 5MB; stored in local filesystem or object storage; up to 10 images per product

## Non-Functional Requirements

### Performance

- NFR1: API response time < 200ms for 95th percentile under normal load (single service call)
- NFR2: Product search via Elasticsearch returns results < 500ms for 95th percentile
- NFR3: Order placement end-to-end (Saga flow) completes within 3 seconds
- NFR4: gRPC inter-service calls complete < 50ms for 95th percentile
- NFR5: WebSocket events delivered to Admin Dashboard within 1 second of occurrence
- NFR6: System handles 100 concurrent order placements without data corruption or overselling
- NFR7: Kafka event processing lag stays under 5 seconds during normal operation
- NFR8: System sustains minimum 50 orders per second under peak load
- NFR9: Service startup to ready state within 30 seconds (K8s readiness probe passes)
- NFR10: Database connection pools (HikariCP) configured per service with max pool size proportional to expected concurrent load — connection acquisition timeout < 5 seconds

### Security

- NFR11: All external API communication encrypted via HTTPS/TLS
- NFR12: All gRPC inter-service communication encrypted via TLS
- NFR13: JWT tokens expire within 15 minutes; refresh tokens within 24 hours
- NFR14: All sensitive configuration (DB passwords, API keys) stored in K8s Secrets, never in code
- NFR15: All user passwords managed by Keycloak with industry-standard hashing (bcrypt/argon2)
- NFR16: API Gateway rejects requests without valid JWT for all protected endpoints
- NFR17: RBAC enforced at API Gateway level — customers cannot access admin endpoints
- NFR18: All state-changing operations logged in audit trail with actor, action, timestamp, and trace ID

### Scalability

- NFR19: Each microservice independently scalable via K8s horizontal pod autoscaling
- NFR20: Kafka consumers support partition-based parallel processing for horizontal scaling
- NFR21: System maintains p95 API latency within 2x of normal-load baselines (NFR1-NFR4) under 10x load increase with proportional horizontal scaling
- NFR22: Database per service pattern ensures independent scaling — each service's database query latency < 100ms at 95th percentile under peak load with no cross-service database dependencies
- NFR23: Redis cluster supports horizontal scaling for cart and caching workloads
- NFR24: Stateless services — no local state, all session data in Redis/DB
- NFR25: Each service operates within K8s resource limits — default request: 256Mi memory / 250m CPU, default limit: 512Mi memory / 500m CPU, adjusted per service based on profiling

### Reliability & Availability

- NFR26: System recovers from single service failure without manual intervention (Circuit Breaker + DLQ)
- NFR27: No data loss during service outages — events persisted via Outbox Pattern before Kafka publish
- NFR28: DLQ messages retained for minimum 7 days before expiry
- NFR29: K8s liveness probes restart unresponsive services within 30 seconds
- NFR30: K8s readiness probes prevent routing traffic to services not yet connected to dependencies
- NFR31: Database migrations (Flyway) executed automatically on service startup without downtime
- NFR32: System maintains data consistency across services within 30 seconds (eventual consistency window)
- NFR33: System continues serving read operations with >95% success rate when any single non-critical service is unavailable — cached data served for product queries, order history remains accessible from Order Service database
- NFR34: System recovers to full operation within 60 seconds after failed service restarts
- NFR35: Kafka consumers use at-least-once delivery with manual offset commit — combined with idempotency to guarantee no message loss and no duplicate processing
- NFR36: Distributed lock TTL set per operation type (default 10 seconds for inventory reservation) — system detects and recovers from expired locks with compensating action
- NFR37: All services execute graceful shutdown within K8s terminationGracePeriodSeconds (default 30 seconds) — zero in-flight request loss during pod termination

### Observability

- NFR38: Every request traceable end-to-end via distributed tracing (unique trace ID)
- NFR39: Correlation ID present in all log entries, error responses, and Kafka message headers
- NFR40: Structured JSON logging across all services with mandatory fields: timestamp (ISO-8601), level, service-name, trace-id, correlation-id, message — enforced via shared logging configuration
- NFR41: Health check endpoints respond within 1 second for both liveness and readiness probes
- NFR42: All services expose metrics via Micrometer/Prometheus endpoint — request rate, error rate (target < 1% under normal load), p50/p95/p99 latency, active connections, and Kafka consumer lag

### Data Consistency & Integrity

- NFR43: Every committed transaction is durable across service boundaries — verified by integration tests that kill services mid-transaction and confirm data recovery via Outbox replay with zero missing events
- NFR44: Saga compensating transactions execute within 10 seconds of failure detection
- NFR45: Idempotency keys prevent duplicate processing for minimum 24 hours

### Maintainability & Code Quality

- NFR46: All services follow shared project structure template (controller/service/repository layers, config package, exception package) validated by ArchUnit tests in CI pipeline
- NFR47: API documentation (OpenAPI/Swagger) auto-generated from code annotations — CI pipeline fails if generated spec differs from committed spec (drift detection)
- NFR48: All `.proto` files versioned with backward-compatible evolution rules — no removing or renaming existing fields, new fields use next available field number, deprecated fields marked with `reserved`, validated by buf lint in CI

### Development & Deployment

- NFR49: CI/CD pipeline executes full build + test + deploy in under 15 minutes
- NFR50: All services deployable independently without coordinated releases
- NFR51: Zero-downtime deployments via K8s rolling update strategy
- NFR52: Test coverage minimum 80% for unit tests per service
- NFR53: All integration tests pass with Testcontainers — no dependency on external environments
- NFR54: Contract tests validate all service-to-service interfaces on every build
- NFR55: CI pipeline blocks deployment if any test (unit, integration, contract) fails
- NFR56: Full test suite (unit + integration + contract) completes within 10 minutes per service
- NFR57: No flaky tests — any test with >1% failure rate must be fixed or quarantined within 24 hours
- NFR58: All API and event schema changes must be backward-compatible — new versions support old consumers for minimum 2 release cycles or 14 days, whichever is longer
- NFR59: Failed test reports include full context: request/response payloads, service logs, and trace IDs for reproducibility
- NFR60: Test data setup and teardown isolated per test execution — no cross-test pollution, each test starts with known state
- NFR61: Chaos testing validates system recovery: random service termination, network latency injection, and resource exhaustion — system returns to healthy state within 60 seconds (per NFR34 recovery target)
- NFR62: Contract tests cover all communication protocols independently: Pact for REST APIs, Protobuf schema validation for gRPC, and schema registry validation for Kafka events
