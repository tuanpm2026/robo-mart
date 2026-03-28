# Story 1.4: Implement Product Search with Full-Text & Filtering

Status: done

## Story

As a customer,
I want to search products by keyword and filter results by price, brand, rating, and category,
so that I can quickly find products matching my specific criteria.

## Acceptance Criteria

1. **Full-Text Search**: Given indexed products in Elasticsearch, when GET /api/v1/products/search?keyword=wireless+headphone, then full-text search returns relevant products ranked by relevance score across name (boosted), description, brand, and categoryName fields (FR2)

2. **Multi-Filter Search**: Given search results, when filters are applied: ?keyword=headphone&minPrice=50&maxPrice=200&brand=Sony&minRating=4&categoryId=3, then results are narrowed to match ALL applied filters simultaneously (FR3)

3. **Partial Filtering**: Given a search request, when only some filters are provided (e.g., only minPrice and brand), then unspecified filters are not applied — partial filtering works correctly

4. **Empty Results Handling**: Given a search with no matching results, when the query returns empty, then response returns `{data: [], pagination: {totalElements: 0, totalPages: 0}, traceId: "..."}`

5. **Pagination Support**: Given search results, when requesting ?page=0&size=20, then pagination metadata (page, size, totalElements, totalPages) is correct (FR73)

6. **Performance**: Given concurrent search requests, when multiple users search simultaneously, then Elasticsearch responds within 500ms for 95th percentile (NFR2)

## Tasks / Subtasks

- [x] Task 1: Create ProductSearchRequest DTO with validation (AC: #1, #2, #3)
  - [x] 1.1 Create `ProductSearchRequest` record in `dto/` with fields: keyword (String, @Size max 200), minPrice (BigDecimal, @DecimalMin "0"), maxPrice (BigDecimal, @DecimalMin "0"), brand (String, @Size max 100), minRating (BigDecimal, @DecimalMin "0", @DecimalMax "5"), categoryId (Long)
  - [x] 1.2 Add validation: if both minPrice and maxPrice provided, minPrice must be <= maxPrice (via @AssertTrue)

- [x] Task 2: Create ProductSearchService with Elasticsearch query logic (AC: #1, #2, #3, #4, #5)
  - [x] 2.1 Create `ProductSearchService` in `service/` — inject `ElasticsearchOperations` and `Tracer`
  - [x] 2.2 Implement `search(ProductSearchRequest request, Pageable pageable)` method returning `PagedResponse<ProductListResponse>`
  - [x] 2.3 Build compound `BoolQuery` using Elasticsearch Java client builders
  - [x] 2.4 Use `NativeQuery.builder().withQuery(...).withPageable(pageable).build()` for query construction
  - [x] 2.5 Execute via `elasticsearchOperations.search(query, ProductDocument.class)`
  - [x] 2.6 Map `SearchHits<ProductDocument>` results to `List<ProductListResponse>`
  - [x] 2.7 Build `PagedResponse` with PaginationMeta from SearchHits.getTotalHits()

- [x] Task 3: Add search endpoint to ProductRestController (AC: #1, #5)
  - [x] 3.1 Add `GET /api/v1/products/search` endpoint to existing `ProductRestController`
  - [x] 3.2 Bind query parameters via `@ModelAttribute @Valid ProductSearchRequest`
  - [x] 3.3 Accept `@PageableDefault(size = 20) Pageable` for pagination
  - [x] 3.4 Return `ResponseEntity<PagedResponse<ProductListResponse>>` — reuse existing response wrapper pattern
  - [x] 3.5 Clamp page size to MAX_PAGE_SIZE (100) consistent with existing endpoints

- [x] Task 4: Write unit tests (AC: #1, #2, #3, #4)
  - [x] 4.1 `ProductSearchServiceTest` — 9 tests: keyword search, multi-filter, partial filters, match_all, empty results, field mapping, pagination, page size clamping, query passing

- [x] Task 5: Write integration tests (AC: #1, #2, #3, #4, #5, #6)
  - [x] 5.1 `ProductSearchIT` — 10 tests: keyword search, multi-filter, partial filters, category filter, brand filter, min rating filter, empty results, pagination metadata, no filters, default pagination
  - [x] 5.2 Use `@BeforeEach` to index known test products and `@AfterEach` to clean up
  - [x] 5.3 Test via RestClient hitting actual search endpoint (same pattern as ProductRestControllerIT)

### Review Findings

- [x] [Review][Patch] Multi-filter IT test should include all 6 filters (add categoryId) [ProductSearchIT.java:83]
- [x] [Review][Patch] Add IT test for validation errors (minPrice > maxPrice → 400) and verify @AssertTrue works with @ModelAttribute on record [ProductSearchIT.java]
- [x] [Review][Defer] GlobalExceptionHandler missing HandlerMethodValidationException handler — validation errors from @ModelAttribute may return 500 [GlobalExceptionHandler.java] — deferred, pre-existing gap
- [x] [Review][Defer] No error handling when Elasticsearch is unavailable — should return 503 [ProductSearchService.java:45] — deferred, Epic 8 scope (circuit breaker)
- [x] [Review][Defer] No sort for match_all queries — non-deterministic pagination [ProductSearchService.java] — deferred, sort not in AC
- [x] [Review][Defer] No relevance ranking test — hard to test reliably with small datasets [ProductSearchIT.java] — deferred
- [x] [Review][Defer] No performance test for 500ms p95 SLA [AC #6] — deferred, requires load testing infrastructure
- [x] [Review][Defer] Sort parameters from Pageable not validated/whitelisted [ProductSearchService.java] — deferred, out of scope

## Dev Notes

### Architecture Compliance

- **Package structure**: `com.robomart.product.{config,controller,dto,entity,repository,service}` — follow existing pattern
- **Layered architecture**: Controller → Service → ElasticsearchOperations → Elasticsearch
- **Search data source**: Elasticsearch ONLY — do NOT query PostgreSQL for search results
- **Response wrapper**: Use existing `PagedResponse<T>` from common-lib — consistent with Story 1.2 REST API
- **No new dependencies**: Spring Data Elasticsearch and Elasticsearch Java client are already in product-service pom.xml (added in Story 1.3)

### Critical Technical Requirements

**Elasticsearch Query Building (Spring Data Elasticsearch 5.x + ES Java Client 9.x)**:

Spring Boot 4.0.4 bundles Spring Data Elasticsearch 5.x which uses the new Elasticsearch Java client (`co.elastic.clients`). Build queries using the functional builder pattern:

```java
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;

NativeQuery query = NativeQuery.builder()
    .withQuery(q -> q.bool(b -> {
        // Full-text search
        if (keyword != null && !keyword.isBlank()) {
            b.must(m -> m.multiMatch(mm -> mm
                .query(keyword)
                .fields("name^3", "description", "brand^2", "categoryName")
            ));
        } else {
            b.must(m -> m.matchAll(ma -> ma));
        }
        // Filters
        if (categoryId != null) {
            b.filter(f -> f.term(t -> t.field("categoryId").value(categoryId)));
        }
        if (brand != null) {
            b.filter(f -> f.term(t -> t.field("brand").value(brand)));
        }
        if (minPrice != null || maxPrice != null) {
            b.filter(f -> f.range(r -> r.number(n -> {
                n.field("price");
                if (minPrice != null) n.gte(minPrice.doubleValue());
                if (maxPrice != null) n.lte(maxPrice.doubleValue());
                return n;
            })));
        }
        return b;
    }))
    .withPageable(pageable)
    .build();

SearchHits<ProductDocument> hits = elasticsearchOperations.search(query, ProductDocument.class);
```

**IMPORTANT — Elasticsearch Java Client 9.x API Changes**:
- Range queries use `.number()` builder (not direct `.gte()` on range)
- `MatchAllQuery` via `.matchAll(ma -> ma)` (lambda required even if empty)
- Field boosting in multi_match: `"name^3"` syntax works in fields list
- `SearchHits.getTotalHits()` returns total count for pagination

**ProductDocument field types** (from Story 1.3 — DO NOT modify):
| Field | ES Type | Search/Filter Use |
|-------|---------|-------------------|
| name | text + keyword (MultiField) | Full-text search (boosted x3) |
| description | text | Full-text search |
| categoryName | keyword | Full-text search + filter term match |
| categoryId | long | Filter (term) |
| brand | keyword | Full-text search (boosted x2) + filter term match |
| price | double | Filter (range) |
| rating | double | Filter (range gte) |
| stockQuantity | integer | Available for future stock filter |
| sku | keyword | Exact match (not used in full-text) |

**Mapping ProductDocument → ProductListResponse**:
ProductListResponse is a record: `(Long id, String sku, String name, BigDecimal price, BigDecimal rating, String brand, Integer stockQuantity, String categoryName, String primaryImageUrl)`

Map from ProductDocument:
- All fields map directly except `primaryImageUrl` — set to null (excluded from JSON by NON_NULL)
- Create a private mapping method or use a dedicated mapper method

### Existing Components to REUSE (DO NOT recreate)

- `ProductDocument` (document/) — ES document model with all field mappings
- `ProductSearchRepository` (repository/) — `ElasticsearchRepository<ProductDocument, Long>` — use for save/delete in tests only; use `ElasticsearchOperations` for search queries
- `ElasticsearchConfig` (config/) — already enables ES repositories
- `ProductRestController` (controller/) — ADD search endpoint here, do NOT create a new controller
- `ProductListResponse` (dto/) — reuse as search result DTO
- `PagedResponse<T>`, `PaginationMeta`, `ApiResponse<T>` (common-lib) — response wrappers
- `ProductService` (service/) — reference for patterns (MAX_PAGE_SIZE, tracer, error handling)
- `GlobalExceptionHandler` (common-lib) — handles validation errors (400), not found (404)
- `@IntegrationTest` (test-support) — already imports Postgres + Kafka + ES containers
- `TestData` builders (test-support) — for creating test entities
- `ElasticsearchContainerConfig` (test-support) — ES 9.1.2 container, already configured

### What NOT to Implement (Out of Scope)

- Redis caching for search results — Story 2.3
- GraphQL search endpoint — Story 1.5
- Search autocomplete/suggestions — not in current scope
- Faceted search (aggregations for filter counts) — future enhancement
- Sort by fields (price, rating, date) — can be added but not in AC
- Circuit breaker / fallback to PostgreSQL when ES is down — Epic 8 (FR56)
- Frontend search UI — Story 1.7

### Testing Requirements

**Test naming**: `should{Expected}When{Condition}()` — e.g., `shouldReturnRelevantProductsWhenKeywordSearch()`

**Assertions**: AssertJ only — `assertThat(result).isEqualTo(expected)`, NOT JUnit assertions

**RestClient pattern** for HTTP tests (Spring Boot 4 — no TestRestTemplate):
```java
restClient = RestClient.builder()
    .baseUrl("http://localhost:" + port)
    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
    .build();
```

**Integration test data setup for search**:
- Index ProductDocuments directly via `ProductSearchRepository.save()` in `@BeforeEach`
- Use `elasticsearchOperations.indexOps(ProductDocument.class).refresh()` after indexing to ensure data is searchable immediately (ES near-real-time)
- Clean up in `@AfterEach` via `ProductSearchRepository.deleteAll()`
- Do NOT rely on Kafka/outbox pipeline for test data — too slow and complex for search tests

**Coverage target**: 80% minimum for new code

### Project Structure Notes

New files to create in product-service:
```
product-service/src/main/java/com/robomart/product/
├── dto/
│   └── ProductSearchRequest.java       # NEW
├── service/
│   └── ProductSearchService.java       # NEW
└── controller/
    └── ProductRestController.java      # MODIFIED (add search endpoint)
```

Test files:
```
product-service/src/test/java/com/robomart/product/
├── unit/
│   ├── service/
│   │   └── ProductSearchServiceTest.java    # NEW
│   └── dto/
│       └── ProductSearchRequestTest.java    # NEW (optional)
└── integration/
    └── search/
        └── ProductSearchIT.java             # NEW
```

### References

- [Source: architecture.md#Product Service structure] — file organization, packages
- [Source: architecture.md#REST API Response Wrapper] — data/pagination/traceId format
- [Source: architecture.md#Elasticsearch Client] — Spring Data Elasticsearch, repository pattern
- [Source: architecture.md#Caching Strategy] — 1min search cache (Story 2.3, out of scope)
- [Source: architecture.md#JSON Conventions] — camelCase, NON_NULL, ISO dates
- [Source: architecture.md#Validation Pattern] — Bean Validation on DTOs
- [Source: prd.md#FR2] — Full-text search by keyword
- [Source: prd.md#FR3] — Filter by price, brand, rating, category
- [Source: prd.md#FR73] — Pagination and sorting
- [Source: prd.md#NFR2] — Search p95 < 500ms
- [Source: epics.md#Story 1.4] — acceptance criteria, dependencies

### Previous Story Intelligence (Story 1.3)

Key learnings to apply:
1. **Jackson 3.x**: Use `tools.jackson.databind` package. No `jackson-datatype-jsr310`.
2. **Testcontainers 2.x**: Artifact names changed. Use `@ServiceConnection` / `DynamicPropertyRegistrar`.
3. **RestClient for tests**: Spring Boot 4 removed `TestRestTemplate`. Use `RestClient` with `defaultStatusHandler`.
4. **@IntegrationTest** already imports PostgresContainerConfig + KafkaContainerConfig + ElasticsearchContainerConfig — no need to add @Import on individual test classes.
5. **ElasticsearchContainerConfig** uses ES 9.1.2 (matches ES Java client 9.x from Spring Boot 4.0.4).
6. **ProductDocument** already has all field mappings needed for search — name (text+keyword MultiField), description (text), brand (keyword), price (double), rating (double), categoryId (long), categoryName (keyword).
7. **Review findings applied**: consumer error handler added, proper timeout handling, null safety — these patterns should be followed in search service error handling.
8. **Deferred work**: No distributed lock, no consumer idempotency via eventId — not relevant for Story 1.4.

## Dev Agent Record

### Agent Model Used
Claude Opus 4.6

### Debug Log References
None — clean implementation, no debug issues.

### Completion Notes List
- All 5 tasks completed in a single pass
- 9 unit tests + 10 integration tests = 19 new tests, all passing
- 27 unit tests total + 28 integration tests total = 55 tests all green
- Added `spring-boot-starter-validation` dependency to product-service pom.xml
- Used `@AssertTrue` for cross-field price range validation on record DTO
- BoolQuery built with ES Java client 9.x functional builder pattern
- `multi_match` on name^3, description, brand^2, categoryName for full-text search
- Term filters for categoryId, brand; range filters for price, rating
- `match_all` used when no keyword provided
- ProductDocument → ProductListResponse mapping with primaryImageUrl=null (excluded by NON_NULL)

### File List
- `product-service/src/main/java/com/robomart/product/dto/ProductSearchRequest.java` (NEW)
- `product-service/src/main/java/com/robomart/product/service/ProductSearchService.java` (NEW)
- `product-service/src/main/java/com/robomart/product/controller/ProductRestController.java` (MODIFIED)
- `product-service/pom.xml` (MODIFIED — added spring-boot-starter-validation)
- `product-service/src/test/java/com/robomart/product/unit/service/ProductSearchServiceTest.java` (NEW)
- `product-service/src/test/java/com/robomart/product/integration/search/ProductSearchIT.java` (NEW)
