# Story 1.2: Implement Product Service with REST Read API

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a customer,
I want to browse products by category and view product details via REST API,
so that I can discover products available for purchase.

## Acceptance Criteria

1. **Database Schema & Seed Data**
   ```
   Given: Product Service with Flyway migrations
   When: the service starts with `demo` Spring profile active
   Then: products, categories, product_images, and outbox_events tables are created automatically via Flyway versioned migrations (FR63)
   And: ~50 seed products across 5 categories with images, descriptions, prices, and ratings are loaded via Flyway repeatable migration (R__seed_products.sql)
   ```

2. **GET /api/v1/products — Browse Products by Category with Pagination**
   ```
   Given: GET /api/v1/products?categoryId=5&page=0&size=20
   When: the request is made
   Then: response returns paginated products filtered by category in REST wrapper format:
         {data: [...], pagination: {page, size, totalElements, totalPages}, traceId} (FR1, FR73)
   ```

3. **GET /api/v1/products/{productId} — View Product Details**
   ```
   Given: GET /api/v1/products/{productId} with a valid ID
   When: the request is made
   Then: response returns full product detail: name, description, specifications, price, images list, stock availability, category, brand, average rating (FR4)
   ```

4. **GET /api/v1/products/{productId} — Not Found Error**
   ```
   Given: GET /api/v1/products/99999 with an invalid ID
   When: the request is made
   Then: response returns 404 with error format:
         {error: {code: "PRODUCT_NOT_FOUND", message: "Product with id 99999 not found"}, traceId, timestamp}
   ```

5. **DTO Mapping with MapStruct**
   ```
   Given: Product entity and DTOs
   When: mapping between them
   Then: MapStruct ProductMapper handles all entity-to-DTO conversions
         (ProductDetailResponse, ProductListResponse)
   ```

6. **Service Structure Compliance**
   ```
   Given: the Product Service source code
   When: inspected
   Then: it follows the prescribed structure: config/, controller/, dto/, entity/, exception/, mapper/, repository/, service/, event/ packages under com.robomart.product
   ```

## Tasks / Subtasks

- [ ] Task 1: Create Flyway versioned migrations (AC: #1)
  - [ ] 1.1 `V1__create_categories_table.sql` — categories table with id, name, description, created_at, updated_at
  - [ ] 1.2 `V2__create_products_table.sql` — products table with all fields, FK to categories, indexes
  - [ ] 1.3 `V3__create_product_images_table.sql` — product_images table with FK to products (ON DELETE CASCADE), display_order
  - [ ] 1.4 `V4__create_outbox_events_table.sql` — outbox_events table (aggregate_type, aggregate_id, event_type, payload JSONB, published, published_at)
  - [ ] 1.5 Add Flyway dependency to product-service pom.xml

- [ ] Task 2: Create seed data migration (AC: #1)
  - [ ] 2.1 `R__seed_products.sql` — repeatable migration with ~50 products across 5 categories
  - [ ] 2.2 Seed categories: Electronics, Home & Kitchen, Sports & Outdoors, Toys & Games, Books
  - [ ] 2.3 Each product: name, description, price (BigDecimal 10,2), sku (unique), rating, category_id, brand
  - [ ] 2.4 Seed product images (2-3 per product, with display_order and alt_text)
  - [ ] 2.5 Use `DELETE FROM` + `INSERT` pattern for idempotent repeatable migration
  - [ ] 2.6 Configure `demo` profile in application-demo.yml to activate seed data via Flyway locations

- [ ] Task 3: Implement JPA entities (AC: #5, #6)
  - [ ] 3.1 `Category.java` — extends BaseEntity, fields: name, description
  - [ ] 3.2 `Product.java` — extends BaseEntity, fields: sku, name, description, price (BigDecimal), categoryId (FK), rating (BigDecimal), brand, stockQuantity. Relationships: @ManyToOne Category, @OneToMany ProductImage
  - [ ] 3.3 `ProductImage.java` — extends BaseEntity, fields: productId (FK), imageUrl, altText, displayOrder. Relationship: @ManyToOne Product
  - [ ] 3.4 Add `@Version` (Integer) field to Product entity for optimistic locking (deferred from Story 1.1 review). This field is internal-only — do NOT expose in any response DTO

- [ ] Task 4: Implement DTOs (AC: #2, #3, #5)
  - [ ] 4.1 `ProductListResponse.java` — record: id, sku, name, price, rating, brand, stockQuantity, categoryName, primaryImageUrl
  - [ ] 4.2 `ProductDetailResponse.java` — record: id, sku, name, description, price, rating, brand, stockQuantity, category (nested: id, name), images (list of: id, imageUrl, altText, displayOrder), createdAt, updatedAt
  - [ ] 4.3 `CategoryResponse.java` — record: id, name, description
  - [ ] 4.4 `ProductImageResponse.java` — record: id, imageUrl, altText, displayOrder

- [ ] Task 5: Implement MapStruct mapper (AC: #5)
  - [ ] 5.1 `ProductMapper.java` — @Mapper(componentModel = "spring") interface
  - [ ] 5.2 Map Product entity → ProductListResponse (derive categoryName from product.category.name, primaryImageUrl from first image ordered by displayOrder ASC — return null if no images)
  - [ ] 5.3 Map Product entity → ProductDetailResponse (map nested category and images list)
  - [ ] 5.4 Unit test: ProductMapperTest

- [ ] Task 6: Implement repositories (AC: #2, #3)
  - [ ] 6.1 `ProductRepository.java` — JpaRepository<Product, Long>, methods: findByCategoryId(Long, Pageable), findAll(Pageable)
  - [ ] 6.2 `CategoryRepository.java` — JpaRepository<Category, Long>

- [ ] Task 7: Implement service layer (AC: #2, #3, #4)
  - [ ] 7.1 `ProductService.java` — @Service class
  - [ ] 7.2 `getProducts(Long categoryId, Pageable pageable)` — returns PagedResponse<ProductListResponse>; if categoryId provided, filter by category; otherwise return all
  - [ ] 7.3 `getProductById(Long productId)` — returns ApiResponse<ProductDetailResponse>; throws ProductNotFoundException if not found
  - [ ] 7.4 Unit test: ProductServiceTest (mock repository, verify pagination, not-found exception)

- [ ] Task 8: Implement exception (AC: #4)
  - [ ] 8.1 `ProductNotFoundException.java` — extends ResourceNotFoundException from common-lib

- [ ] Task 9: Implement REST controller (AC: #2, #3, #4)
  - [ ] 9.1 `ProductRestController.java` — @RestController @RequestMapping("/api/v1/products")
  - [ ] 9.2 `GET /` — browse products with optional categoryId filter, pagination (page, size with defaults 0, 20). Enforce max size 100 in controller: if size > 100, clamp to 100 (do not throw error)
  - [ ] 9.3 `GET /{productId}` — view product detail
  - [ ] 9.4 Add SpringDoc OpenAPI annotations (@Operation, @ApiResponse, @Parameter)

- [ ] Task 10: Add product-specific error codes (AC: #4)
  - [ ] 10.1 Add `PRODUCT_NOT_FOUND` to ErrorCode enum in common-lib (if not already present; check existing enum values first)

- [ ] Task 11: Update product-service dependencies (AC: #1, #5)
  - [ ] 11.1 Add to product-service pom.xml: spring-boot-starter-data-jpa (verify not duplicate), flyway-core, flyway-database-postgresql, postgresql driver, mapstruct + mapstruct-processor, springdoc-openapi-starter-webmvc-ui

- [ ] Task 12: Configure application profiles (AC: #1)
  - [ ] 12.1 Update `application.yml` — Flyway enabled, migration locations classpath:db/migration
  - [ ] 12.2 Create/update `application-demo.yml` — additional Flyway locations: classpath:db/seed
  - [ ] 12.3 Verify `application-dev.yml` has JPA ddl-auto: validate (Flyway manages schema)
  - [ ] 12.4 Verify `application-test.yml` for Testcontainers PostgreSQL override

- [ ] Task 13: Write integration tests (AC: #2, #3, #4)
  - [ ] 13.1 `ProductRestControllerIT.java` — @SpringBootTest + Testcontainers PostgreSQL
  - [ ] 13.2 Test: GET /api/v1/products returns paginated products with correct wrapper format
  - [ ] 13.3 Test: GET /api/v1/products?categoryId=X returns only products in that category
  - [ ] 13.4 Test: GET /api/v1/products/{id} returns full product detail with images
  - [ ] 13.5 Test: GET /api/v1/products/99999 returns 404 with error format
  - [ ] 13.6 Test: pagination parameters (page, size) work correctly
  - [ ] 13.7 `ProductRepositoryIT.java` — verify repository queries with Testcontainers

- [ ] Task 14: Set up test-support module (AC: #2, #3)
  - [ ] 14.1 Create `PostgresContainerConfig.java` in test-support module — static singleton PostgreSQLContainer<> (Testcontainers PostgreSQL 17). Use `@ServiceConnection` from spring-boot-testcontainers to auto-configure datasource. Register as `@TestConfiguration(proxyBeanMethods = false)` importable by test classes
  - [ ] 14.2 Create `@IntegrationTest` custom annotation — meta-annotation combining @SpringBootTest, @ActiveProfiles("test"), @Import(PostgresContainerConfig.class)
  - [ ] 14.3 Create `TestData.java` builder — product(), category(), productImage() builders with fluent API. Do NOT hardcode IDs — let the database assign them
  - [ ] 14.4 Add test-support dependencies: org.testcontainers:testcontainers, org.testcontainers:postgresql, org.testcontainers:junit-jupiter, org.springframework.boot:spring-boot-testcontainers

## Dev Notes

### Architecture Compliance

- **Package structure**: `com.robomart.product.{config,controller,dto,entity,exception,mapper,repository,service,event}` — already scaffolded in Story 1.1
- **Layered architecture**: Controller → Service → Repository → Entity (no business logic in controllers)
- **REST API versioning**: URL path `/api/v1/` prefix
- **Response wrapper**: Use `ApiResponse<T>` for single items, `PagedResponse<T>` for lists — from common-lib
- **Error responses**: Use `ApiErrorResponse` from common-lib via GlobalExceptionHandler
- **Entity inheritance**: All entities extend `BaseEntity` from common-lib (id, createdAt, updatedAt)
- **Scan base packages**: `@SpringBootApplication(scanBasePackages = "com.robomart")` — already configured

### Jackson 3.x Warning (Spring Boot 4)

Any custom Jackson code in this service MUST use `tools.jackson.databind` package — NOT `com.fasterxml.jackson.databind`. Annotations remain in `com.fasterxml.jackson.annotation` (unchanged). The common-lib JacksonConfig already handles global configuration via `JsonMapperBuilderCustomizer`. Do not add jackson-datatype-jsr310 — Java Time support is built into Jackson 3.x.

### Critical Technical Requirements

**Database Schema (PostgreSQL):**
```sql
-- Categories
CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Products
CREATE TABLE products (
    id           BIGSERIAL PRIMARY KEY,
    sku          VARCHAR(100) NOT NULL UNIQUE,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    price        NUMERIC(10, 2) NOT NULL,
    category_id  BIGINT NOT NULL REFERENCES categories(id),
    rating       NUMERIC(3, 2) DEFAULT 0.0,
    brand        VARCHAR(255),
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    version      INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_products_sku ON products(sku);
CREATE INDEX idx_products_created_at ON products(created_at DESC);

-- Product Images
CREATE TABLE product_images (
    id            BIGSERIAL PRIMARY KEY,
    product_id    BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    image_url     VARCHAR(500) NOT NULL,
    alt_text      VARCHAR(255),
    display_order INTEGER DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_product_images_product_id ON product_images(product_id);

-- Outbox Events (created now, used by Story 1.3)
CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    published_at   TIMESTAMP
);
CREATE INDEX idx_outbox_events_published ON outbox_events(published) WHERE published = FALSE;
```

**REST API Response Formats (from common-lib):**

Success (single item):
```json
{
  "data": { "id": 1, "name": "Robot Toy", "price": 29.99, ... },
  "traceId": "abc123"
}
```

Success (paginated list):
```json
{
  "data": [ ... ],
  "pagination": { "page": 0, "size": 20, "totalElements": 156, "totalPages": 8 },
  "traceId": "abc123"
}
```

Error:
```json
{
  "error": { "code": "PRODUCT_NOT_FOUND", "message": "Product with id 42 not found", "details": null },
  "traceId": "abc123",
  "timestamp": "2026-03-27T10:30:00Z"
}
```

**JSON Conventions:**
- Field naming: camelCase (configured globally in common-lib JacksonConfig)
- Null fields excluded (NON_NULL configured in JacksonConfig)
- Dates: ISO-8601 UTC (Jackson 3.x default)
- Money: BigDecimal serialized as number with 2 decimal places

### Existing common-lib Components to REUSE (DO NOT recreate)

- `ApiResponse<T>` — success wrapper with data + traceId
- `PagedResponse<T>` — paginated response with data, pagination (PaginationMeta), traceId
- `ApiErrorResponse` — error response wrapper
- `PaginationMeta` — page, size, totalElements, totalPages
- `ErrorCode` enum — add PRODUCT_NOT_FOUND if not present
- `ResourceNotFoundException` — base class for ProductNotFoundException
- `GlobalExceptionHandler` — automatically handles exceptions → error responses
- `BaseEntity` — id, createdAt, updatedAt (with protected setId, no setCreatedAt/setUpdatedAt)
- `CorrelationIdFilter` — already propagates X-Correlation-Id
- `JacksonConfig` — already configures camelCase + NON_NULL + ISO dates via `JsonMapperBuilderCustomizer`

### Technology & Library Requirements

| Dependency | Artifact | Notes |
|-----------|----------|-------|
| Spring Data JPA | spring-boot-starter-data-jpa | Already in product-service pom.xml |
| Flyway Core | flyway-core | Add to product-service |
| Flyway PostgreSQL | flyway-database-postgresql | Required for PostgreSQL dialect |
| PostgreSQL Driver | postgresql | Already in product-service pom.xml |
| MapStruct | mapstruct | Version managed in parent POM (1.6.3) |
| MapStruct Processor | mapstruct-processor | Annotation processor, scope: provided |
| SpringDoc OpenAPI | springdoc-openapi-starter-webmvc-ui | API documentation |

**MapStruct Configuration:**
- Add `mapstruct-processor` as annotation processor in maven-compiler-plugin
- Use `@Mapper(componentModel = "spring")` — auto-registers as Spring bean
- Mapper interface generates implementation at compile time

### Flyway Configuration

**Migration file locations:**
- Schema migrations: `src/main/resources/db/migration/` (always loaded)
- Seed data: `src/main/resources/db/seed/` (loaded only with `demo` profile)

**application.yml:**
```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

**application-demo.yml:**
```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/seed
```

**Naming conventions:**
- Versioned: `V1__create_categories_table.sql`, `V2__create_products_table.sql`
- Repeatable: `R__seed_products.sql` (re-executed when content changes)

### Seed Data Requirements

~50 products across 5 categories. Each product includes:
- Unique SKU (e.g., "ELEC-001", "HOME-015")
- Name, description, price, brand, rating (0.0-5.0), stock_quantity
- 2-3 images per product with display_order and alt_text
- Use realistic product data for demo purposes

Categories: Electronics, Home & Kitchen, Sports & Outdoors, Toys & Games, Books

Repeatable migration pattern:
```sql
DELETE FROM product_images;
DELETE FROM products;
DELETE FROM categories;

INSERT INTO categories (id, name, description) VALUES ...;
INSERT INTO products (id, sku, name, ...) VALUES ...;
INSERT INTO product_images (id, product_id, image_url, ...) VALUES ...;

-- Reset sequences
SELECT setval('categories_id_seq', (SELECT MAX(id) FROM categories));
SELECT setval('products_id_seq', (SELECT MAX(id) FROM products));
SELECT setval('product_images_id_seq', (SELECT MAX(id) FROM product_images));
```

**Important:** Seed data IDs are unstable (reset on every migration run). Do NOT hardcode product IDs anywhere in application code or tests. Always query by name/sku or use TestData builders.

### Testing Requirements

**Test Naming Convention:** `should{Expected}When{Condition}()`
- Example: `shouldReturnProductWhenValidId()`, `shouldReturn404WhenProductNotFound()`

**Assertions:** Use AssertJ (NOT JUnit assertions)
```java
assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
assertThat(body.data()).hasSize(10);
```

**Test Data:** Use TestData builders from test-support module
```java
Product product = TestData.product()
    .withName("Robot Toy")
    .withPrice(BigDecimal.valueOf(29.99))
    .withCategory(category)
    .build();
```

**Integration Tests:**
- Use `@IntegrationTest` annotation (to be created in test-support)
- Testcontainers PostgreSQL 17 singleton instance
- Load seed data for integration tests
- Test actual HTTP requests with `TestRestTemplate` or `WebTestClient`

**Coverage Target:** 80% minimum for new code

### Performance Requirements

- API p95 < 200ms for single service call (NFR1)
- Database query p95 < 100ms under peak load (NFR22)
- Required indexes: categoryId, sku, created_at (see SQL schema above)
- Service startup < 30s (NFR8)
- Pagination defaults: page=0, size=20, max size=100

### Security Notes (Story 1.2 scope)

- Product browse/search endpoints are PUBLIC (no auth required)
- `/api/v1/products/**` — no authentication needed
- Admin CRUD endpoints are NOT in scope for this story
- Do NOT add Spring Security configuration yet (Epic 3)

### Outbox Events Table

- Created as part of Flyway migrations in this story
- NOT used/processed in this story — Story 1.3 implements the outbox poller
- Table schema includes: aggregate_type, aggregate_id, event_type, payload (JSONB), published flag
- Partial index on `published = FALSE` for efficient polling

### What NOT to Implement (Out of Scope)

- Admin CRUD endpoints (POST, PUT, DELETE) — Epic 5
- Elasticsearch integration — Story 1.3
- Product search with full-text filtering — Story 1.4
- GraphQL endpoint — Story 1.5
- Redis caching — Story 2.3
- gRPC ProductService — deferred
- Kafka event publishing — Story 1.3
- Spring Security / JWT — Epic 3
- CreateProductRequest, UpdateProductRequest DTOs — not needed for read-only API

### Previous Story Intelligence (Story 1.1)

**Key Learnings Applied:**
1. **Jackson 3.x**: Package is `tools.jackson.databind` (NOT `com.fasterxml.jackson.databind`). JacksonConfig uses `JsonMapperBuilderCustomizer` from `org.springframework.boot.jackson.autoconfigure`. No jackson-datatype-jsr310 needed.
2. **commons-logging**: Do NOT ban in maven-enforcer-plugin — Spring Framework 7 depends on it directly.
3. **scanBasePackages**: ProductServiceApplication already has `scanBasePackages = "com.robomart"`.
4. **BaseEntity**: setId() is protected, no setCreatedAt/setUpdatedAt. Use @CreationTimestamp and @UpdateTimestamp.
5. **CorrelationIdFilter**: Already validates max length (128 chars) and generates UUID if missing.
6. **GlobalExceptionHandler**: Already handles MethodArgumentNotValidException (400) and HttpRequestMethodNotSupportedException (405). Has null-safe traceId extraction from Micrometer Tracer.
7. **Structured logging**: logback-spring.xml configured with JSON format (prod) and human-readable console (dev). Uses LogstashEncoder.
8. **Maven parent POM**: spring-boot-starter-parent:4.0.4, Java 21, Spring Cloud 2025.1.1. MapStruct 1.6.3 in dependencyManagement.

**Deferred Items to Address:**
- Add `@Version` field to entities for optimistic locking (deferred from Story 1.1 code review)

**Files Created in Story 1.1 (for reference):**
- `backend/common-lib/` — all DTOs, exceptions, config, filter, logging
- `backend/product-service/pom.xml` — dependencies: common-lib, events, web, data-jpa, actuator, postgresql
- `backend/product-service/src/main/resources/application.yml` — port 8081, PostgreSQL config
- `backend/events/` — Avro base event envelope
- `backend/test-support/` — placeholder (to be implemented in this story)
- `infra/docker/docker-compose.yml` — PostgreSQL on port 5432, product_db database

### Project Structure Notes

Files to create/modify in this story:
```
backend/product-service/
├── pom.xml                                          # ADD: flyway, mapstruct, springdoc deps
├── src/main/java/com/robomart/product/
│   ├── config/                                      # (empty, no config needed for 1.2)
│   ├── controller/
│   │   └── ProductRestController.java               # NEW
│   ├── dto/
│   │   ├── ProductListResponse.java                 # NEW
│   │   ├── ProductDetailResponse.java               # NEW
│   │   ├── CategoryResponse.java                    # NEW
│   │   └── ProductImageResponse.java                # NEW
│   ├── entity/
│   │   ├── Product.java                             # NEW
│   │   ├── Category.java                            # NEW
│   │   └── ProductImage.java                        # NEW
│   ├── exception/
│   │   └── ProductNotFoundException.java            # NEW
│   ├── mapper/
│   │   └── ProductMapper.java                       # NEW
│   ├── repository/
│   │   ├── ProductRepository.java                   # NEW
│   │   └── CategoryRepository.java                  # NEW
│   ├── service/
│   │   └── ProductService.java                      # NEW
│   └── event/                                       # (empty, Story 1.3)
├── src/main/resources/
│   ├── application.yml                              # MODIFY: add flyway config
│   ├── application-demo.yml                         # NEW: demo profile with seed locations
│   └── db/
│       ├── migration/
│       │   ├── V1__create_categories_table.sql       # NEW
│       │   ├── V2__create_products_table.sql         # NEW
│       │   ├── V3__create_product_images_table.sql   # NEW
│       │   └── V4__create_outbox_events_table.sql    # NEW
│       └── seed/
│           └── R__seed_products.sql                  # NEW
└── src/test/java/com/robomart/product/
    ├── unit/
    │   ├── service/
    │   │   └── ProductServiceTest.java              # NEW
    │   └── mapper/
    │       └── ProductMapperTest.java               # NEW
    └── integration/
        ├── controller/
        │   └── ProductRestControllerIT.java         # NEW
        └── repository/
            └── ProductRepositoryIT.java             # NEW

backend/test-support/
├── pom.xml                                          # MODIFY: add testcontainers deps
└── src/main/java/com/robomart/test/
    ├── PostgresContainerConfig.java                 # NEW
    ├── IntegrationTest.java                         # NEW (annotation)
    └── TestData.java                                # NEW (builders)

backend/common-lib/
└── src/main/java/com/robomart/common/logging/
    └── ErrorCode.java                               # MODIFY: add PRODUCT_NOT_FOUND (if not present)
```

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Epic 1, Story 1.2] — User story, acceptance criteria, technical requirements
- [Source: _bmad-output/planning-artifacts/architecture.md#Product Service] — Database schema, API patterns, service structure
- [Source: _bmad-output/planning-artifacts/architecture.md#REST API Conventions] — Response wrapper format, HTTP status codes, JSON conventions
- [Source: _bmad-output/planning-artifacts/architecture.md#Testing Standards] — Test naming, TestData builders, Testcontainers, coverage targets
- [Source: _bmad-output/planning-artifacts/architecture.md#Flyway Migrations] — Migration naming, seed data strategy
- [Source: _bmad-output/planning-artifacts/prd.md#FR1,FR4,FR63,FR73] — Functional requirements covered
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Product Card] — Product data fields needed for UI (image, title, price, rating, stock badge)
- [Source: _bmad-output/implementation-artifacts/1-1-scaffold-monorepo-minimal-development-infrastructure.md] — Previous story learnings, code review patches, project structure

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
