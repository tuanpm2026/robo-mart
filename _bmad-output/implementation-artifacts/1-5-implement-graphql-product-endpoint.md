# Story 1.5: Implement GraphQL Product Endpoint

Status: done

## Story

As a customer,
I want to query products via GraphQL with flexible filtering and nested data,
So that I can retrieve exactly the product data I need in a single request.

## Acceptance Criteria

1. **Single Product Query**: Given GraphQL endpoint at /graphql, when I send a query `{ product(id: 1) { id name price category { name } images { imageUrl } } }`, then I receive only the requested fields with nested category and images resolved (FR6)

2. **Product List Query with Filters**: Given GraphQL endpoint, when I query products with filter arguments `{ products(categoryId: 5, minPrice: 10, keyword: "robot") { content { id name price } totalElements } }`, then I receive filtered results with only requested fields

3. **Batch Loading (N+1 Prevention)**: Given a product query requesting nested data from multiple entities, when using @BatchMapping for category and images, then the N+1 problem is avoided — batch loading via DataLoader pattern is used

4. **Native GraphQL Response Format**: Given GraphQL response, when returned to client, then it follows native GraphQL spec format (`{data: {...}}` or `{data: null, errors: [...]}`) — NOT the REST API response wrapper

5. **GraphQL Schema File**: Given the GraphQL schema file, when inspected at src/main/resources/graphql/schema.graphqls, then it defines Product, Category, ProductImage types with proper relationships and query entry points

## Tasks / Subtasks

- [x] Task 1: Add GraphQL dependencies to product-service pom.xml (AC: #5)
  - [x] 1.1 Add `spring-boot-starter-graphql` dependency

- [x] Task 2: Create GraphQL schema file (AC: #1, #2, #5)
  - [x] 2.1 Create `src/main/resources/graphql/schema.graphqls`
  - [x] 2.2 Define Product, Category, ProductImage types
  - [x] 2.3 Define ProductConnection type for paginated results
  - [x] 2.4 Define Query type with product(id) and products(filters) entry points

- [x] Task 3: Create supporting classes (AC: #2, #3)
  - [x] 3.1 Create `ProductImageRepository` with batch loading query
  - [x] 3.2 Create `ProductConnection` record DTO for paginated GraphQL results

- [x] Task 4: Create ProductGraphQLController (AC: #1, #2, #3, #4)
  - [x] 4.1 Implement @QueryMapping for product(id) — load from PostgreSQL
  - [x] 4.2 Implement @QueryMapping for products(filters) — search ES for IDs + load entities from PostgreSQL
  - [x] 4.3 Implement @BatchMapping for category — batch load categories by FK IDs
  - [x] 4.4 Implement @BatchMapping for images — batch load images by product IDs

- [x] Task 5: Write integration tests (AC: #1, #2, #3, #4, #5)
  - [x] 5.1 ProductGraphQLIT — test product query by ID with nested data
  - [x] 5.2 Test product not found returns null
  - [x] 5.3 Test products query with keyword filter via ES
  - [x] 5.4 Test products query with multiple filters
  - [x] 5.5 Test empty results
  - [x] 5.6 Test native GraphQL response format (no REST wrapper)

### Review Findings

- [x] [Review][Patch] Add null safety in category BatchMapping — filter products with null/missing categories [ProductGraphQLController.java:107-119]
- [x] [Review][Patch] Validate page >= 0 and size >= 1 to prevent IllegalArgumentException from negative values [ProductGraphQLController.java:67-68]
- [x] [Review][Defer] totalElements uses int cast from long — overflow risk for >2.1B products [ProductConnection.java:9] — deferred, unrealistic for current scale
- [x] [Review][Defer] Stale ES data may cause totalElements mismatch with actual content count — eventual consistency gap [ProductGraphQLController.java:90-96] — deferred, same pattern as REST search
- [x] [Review][Defer] Schema field name `imageUrl` differs from AC example `url` — AC example is illustrative, `imageUrl` matches entity field naming [schema.graphqls:38] — deferred, cosmetic
- [x] [Review][Defer] No explicit GraphQL error handling for repository exceptions — Spring for GraphQL default DataFetcherExceptionResolver handles gracefully [ProductGraphQLController.java] — deferred, Epic 8 scope

## Dev Notes

### Architecture Compliance

- **Package structure**: `com.robomart.product.controller.ProductGraphQLController`
- **Response format**: Native GraphQL spec — do NOT use ApiResponse/PagedResponse wrappers
- **Data sources**: product(id) → PostgreSQL, products(filters) → Elasticsearch IDs + PostgreSQL entities
- **@BatchMapping**: Batch load category (by FK IDs) and images (by product IDs) to avoid N+1

### Critical Technical Requirements

**Spring for GraphQL (bundled with Spring Boot 4.0.4)**:
- Use `@Controller` (not @RestController) with `@QueryMapping`, `@BatchMapping`
- Schema file at `src/main/resources/graphql/schema.graphqls`
- Endpoint auto-configured at `/graphql`

**@BatchMapping for JPA lazy associations**:
- Product.category is ManyToOne LAZY — `product.getCategory().getId()` returns FK value without triggering lazy load (Hibernate stores FK value in proxy)
- Product.images is OneToMany LAZY — load via ProductImageRepository batch query
- Works on detached entities

**Products query strategy**:
1. Reuse ProductSearchService.search() for Elasticsearch filtering
2. Extract IDs from search results
3. Load full entities from PostgreSQL via findAllById()
4. Preserve ES relevance ordering

### Existing Components to REUSE

- `ProductSearchService` (service/) — for ES search in products query
- `ProductSearchRequest` (dto/) — for search parameters
- `ProductRepository` (repository/) — for findById and findAllById
- `CategoryRepository` (repository/) — for batch loading categories
- `Product`, `Category`, `ProductImage` entities — return directly from GraphQL
- `@IntegrationTest` (test-support) — imports all container configs

### What NOT to Implement

- GraphQL mutations (product CRUD) — Admin stories scope
- GraphQL subscriptions — not in scope
- Custom scalar types — use Float for BigDecimal, String for Instant
- Input validation beyond GraphQL schema types
- Caching for GraphQL queries — future enhancement
- GraphiQL UI — can add later

### Testing Requirements

- **Test naming**: `should{Expected}When{Condition}()`
- **Assertions**: AssertJ only
- **RestClient pattern** for HTTP tests: POST to /graphql with JSON body
- **ES test data**: Index ProductDocuments in @BeforeEach, clean up in @AfterEach
- **PostgreSQL test data**: From seed migration (products with IDs 1+ exist)

### References

- [Source: architecture.md] — Spring for GraphQL, @BatchMapping, native GraphQL response format
- [Source: epics.md#Story 1.5] — acceptance criteria
- [Source: prd.md#FR6] — GraphQL product queries
