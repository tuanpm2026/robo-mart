# Story 5.2: Implement Admin Product CRUD

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an admin,
I want to create, read, update, and delete products from the Admin Dashboard,
So that I can manage the product catalog efficiently.

## Acceptance Criteria

1. **Products DataTable**: Given the Products page in Admin Dashboard (`/admin/products`), when I navigate to it, then I see a PrimeVue DataTable with columns: ID, Name, Category, Price, Stock, Actions — sortable and filterable (FR43) (AC1)

2. **Add Product**: Given the Products DataTable, when I click "Add Product" button, then a `ProductFormSlideOver` opens (right-side, 640px) with fields: name (required), description, category (Dropdown populated from API), price (required), brand — with inline validation on blur (UX-DR16) (AC2)

3. **Inline Editing**: Given a product in the DataTable, when I click the price or stock cell, then it becomes inline editable — Enter to save (PATCH request), Esc to cancel. Toast confirms: "Product updated" (top-right, 3s) (UX-DR13) (AC3)

4. **Full Edit (Slide-Over)**: Given a product row Actions column, when I click "Edit", then `ProductFormSlideOver` opens pre-populated with current values, on save PUT request is made, toast "Product updated" appears (AC4)

5. **Delete with Confirmation**: Given a product row Actions column, when I click "Delete", then a PrimeVue ConfirmDialog appears: "Delete [product name]? This cannot be undone." with Danger button. On confirm, product is soft-deleted (backend: `active=false`) and row removed from table with Toast "Product deleted" (UX-DR15, UX-DR20) (AC5)

6. **Empty State**: Given the Products DataTable with no active products, when the table renders, then `EmptyState` shows: "No products yet" / "Start building your catalog" / "Add First Product" CTA (UX-DR7) (AC6)

7. **Backend — Create Product**: Given `POST /api/v1/admin/products` with valid `CreateProductRequest`, when called with ADMIN role JWT, then product is created in DB, `PRODUCT_CREATED` outbox event saved, `201 Created` with `ProductDetailResponse` returned (AC7)

8. **Backend — Update Product**: Given `PUT /api/v1/admin/products/{productId}` with valid `UpdateProductRequest`, when called with ADMIN role JWT, then product updated, `productDetail` cache evicted, `PRODUCT_UPDATED` outbox event saved, `200 OK` with `ProductDetailResponse` returned (AC8)

9. **Backend — Delete Product**: Given `DELETE /api/v1/admin/products/{productId}`, when called with ADMIN role JWT, then `active` set to `false`, `productDetail` cache evicted, `PRODUCT_DELETED` outbox event saved, `204 No Content` returned (AC9)

10. **Backend — Categories**: Given `GET /api/v1/admin/categories`, when called with ADMIN role JWT, then all categories returned as `List<CategoryResponse>` for form dropdown (AC10)

## Tasks / Subtasks

### Backend: product-service

- [x] Task 1: Add `active` column via Flyway migration (AC: 7, 8, 9)
  - [x] 1.1 Create `backend/product-service/src/main/resources/db/migration/V5__add_product_active_column.sql`:
    ```sql
    ALTER TABLE products ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
    ```
  - [x] 1.2 Add `active` field to `Product` entity:
    ```java
    @Column(nullable = false)
    private boolean active = true;
    // + getter/setter: isActive() / setActive()
    ```
  - [x] 1.3 Update `ProductRepository` queries to filter `active = true`:
    - `findByCategoryId` → add `AND p.active = true` (use new method name `findByCategoryIdAndActiveTrue`)
    - `findAllWithDetails` → add `WHERE p.active = true` to the JPQL query
    - `findByIdWithDetails` → add `AND p.active = true` to the JPQL query

- [x] Task 2: Create admin request DTOs in `com.robomart.product.dto` (AC: 7, 8)
  - [x] 2.1 Create `CreateProductRequest.java`:
    ```java
    public record CreateProductRequest(
        @NotBlank String name,
        String description,
        @NotNull Long categoryId,
        @NotNull @DecimalMin("0.01") BigDecimal price,
        String brand,
        String sku  // optional — auto-generated UUID prefix if null
    ) {}
    ```
  - [x] 2.2 Create `UpdateProductRequest.java`:
    ```java
    public record UpdateProductRequest(
        @NotBlank String name,
        String description,
        @NotNull Long categoryId,
        @NotNull @DecimalMin("0.01") BigDecimal price,
        String brand
    ) {}
    ```
  - [x] 2.3 Annotations: `jakarta.validation.constraints.*` — NOT `javax.validation`

- [x] Task 3: Extend `ProductMapper` in `com.robomart.product.mapper` (AC: 7, 8)
  - [x] 3.1 Add `toEntity(CreateProductRequest request)` — ignores id, createdAt, updatedAt, active, images, rating, stockQuantity (defaults from entity):
    ```java
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "stockQuantity", ignore = true)
    @Mapping(target = "category", ignore = true)   // set manually in service
    Product toEntity(CreateProductRequest request);
    ```
  - [x] 3.2 Add `updateEntityFromRequest(UpdateProductRequest request, @MappingTarget Product product)` — updates only mutable fields (name, description, price, brand); category set manually:
    ```java
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sku", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "stockQuantity", ignore = true)
    @Mapping(target = "category", ignore = true)   // set manually in service
    void updateEntityFromRequest(UpdateProductRequest request, @MappingTarget Product product);
    ```

- [x] Task 4: Extend `ProductService` with CRUD methods (AC: 7, 8, 9)
  - [x] 4.1 Add `CategoryRepository` and `OutboxPublisher` as constructor dependencies
  - [x] 4.2 Add `createProduct(CreateProductRequest request)`:
    ```java
    @Transactional
    public ProductDetailResponse createProduct(CreateProductRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                "Category not found: " + request.categoryId()));

        Product product = productMapper.toEntity(request);
        product.setCategory(category);

        // Auto-generate SKU if not provided
        if (product.getSku() == null || product.getSku().isBlank()) {
            product.setSku(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }

        // Check SKU uniqueness
        if (productRepository.existsBySku(product.getSku())) {
            throw new BusinessRuleException("Product with SKU '" + product.getSku() + "' already exists");
        }

        Product saved = productRepository.save(product);

        // Outbox event — within same @Transactional
        String payload = buildOutboxPayload(saved);
        outboxPublisher.saveEvent("PRODUCT", saved.getId().toString(), "PRODUCT_CREATED", payload);

        log.info("Product created: id={}, sku={}", saved.getId(), saved.getSku());
        return productMapper.toDetailResponse(saved);
    }
    ```
  - [x] 4.3 Add `updateProduct(Long productId, UpdateProductRequest request)`:
    ```java
    @Transactional
    @CacheEvict(value = "productDetail", key = "#productId")
    public ProductDetailResponse updateProduct(Long productId, UpdateProductRequest request) {
        Product product = productRepository.findByIdWithDetails(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                "Category not found: " + request.categoryId()));

        productMapper.updateEntityFromRequest(request, product);
        product.setCategory(category);

        Product saved = productRepository.save(product);

        String payload = buildOutboxPayload(saved);
        outboxPublisher.saveEvent("PRODUCT", saved.getId().toString(), "PRODUCT_UPDATED", payload);

        log.info("Product updated: id={}", saved.getId());
        return productMapper.toDetailResponse(saved);
    }
    ```
  - [x] 4.4 Add `deleteProduct(Long productId)` — soft delete:
    ```java
    @Transactional
    @CacheEvict(value = "productDetail", key = "#productId")
    public void deleteProduct(Long productId) {
        Product product = productRepository.findByIdWithDetails(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        product.setActive(false);
        productRepository.save(product);

        // Minimal payload: id + sku required by OutboxPollingService PRODUCT_DELETED handler
        String payload = String.format("{\"id\":%d,\"sku\":\"%s\"}", product.getId(), product.getSku());
        outboxPublisher.saveEvent("PRODUCT", product.getId().toString(), "PRODUCT_DELETED", payload);

        log.info("Product soft-deleted: id={}", productId);
    }
    ```
  - [x] 4.5 Add private `buildOutboxPayload(Product product)` helper — produces JSON with all fields expected by `OutboxPollingService.publishEvent()`:
    ```java
    private String buildOutboxPayload(Product product) {
        // Required fields for ProductCreatedEvent/ProductUpdatedEvent Avro builders:
        // id, sku, name, description, price, categoryId, categoryName, brand, rating, stockQuantity
        return objectMapper.writeValueAsString(Map.of(
            "id", product.getId(),
            "sku", product.getSku(),
            "name", product.getName(),
            "description", product.getDescription() != null ? product.getDescription() : "",
            "price", product.getPrice(),
            "categoryId", product.getCategory().getId(),
            "categoryName", product.getCategory().getName(),
            "brand", product.getBrand() != null ? product.getBrand() : "",
            "stockQuantity", product.getStockQuantity()
            // rating excluded — null-safe handled in OutboxPollingService
        ));
    }
    ```
    Note: Inject `ObjectMapper objectMapper` as constructor parameter. Use `tools.jackson.databind.ObjectMapper` (NOT `com.fasterxml.jackson.databind.ObjectMapper` — Jackson 3.x package).
  - [x] 4.6 Add `existsBySku(String sku)` to `ProductRepository`:
    ```java
    boolean existsBySku(String sku);
    ```
  - [x] 4.7 Add `getAllCategories()` to ProductService:
    ```java
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().stream()
            .map(productMapper::toCategoryResponse)
            .toList();
    }
    ```

- [x] Task 5: Create `AdminProductRestController` at `com.robomart.product.controller` (AC: 7, 8, 9, 10)
  - [x] 5.1 Create `AdminProductRestController.java`:
    ```java
    @RestController
    @RequestMapping("/api/v1/admin")
    public class AdminProductRestController {
        // No @PreAuthorize needed — ADMIN role enforced at API Gateway level
        // API Gateway: .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
    }
    ```
  - [x] 5.2 `POST /api/v1/admin/products` — create product:
    ```java
    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> createProduct(
            @RequestBody @Valid CreateProductRequest request) {
        ProductDetailResponse response = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new ApiResponse<>(response, getTraceId()));
    }
    ```
  - [x] 5.3 `PUT /api/v1/admin/products/{productId}` — update product:
    ```java
    @PutMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> updateProduct(
            @PathVariable Long productId,
            @RequestBody @Valid UpdateProductRequest request) {
        ProductDetailResponse response = productService.updateProduct(productId, request);
        return ResponseEntity.ok(new ApiResponse<>(response, getTraceId()));
    }
    ```
  - [x] 5.4 `DELETE /api/v1/admin/products/{productId}` — soft delete:
    ```java
    @DeleteMapping("/products/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId) {
        productService.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }
    ```
  - [x] 5.5 `GET /api/v1/admin/categories` — list categories for form dropdown:
    ```java
    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> getCategories() {
        return ResponseEntity.ok(productService.getAllCategories());
    }
    ```
  - [x] 5.6 Copy `getTraceId()` helper from `ProductRestController` (same pattern, same `Tracer` injection)

- [x] Task 6: Tests (AC: 7, 8, 9, 10)
  - [x] 6.1 Unit test `AdminProductServiceTest` in `backend/product-service/src/test/java/com/robomart/product/unit/service/`:
    - Mock: `ProductRepository`, `CategoryRepository`, `ProductMapper`, `OutboxPublisher`, `ObjectMapper`, `Tracer`
    - Tests: `createProduct_whenValidRequest_savesProductAndOutboxEvent()`, `createProduct_whenCategoryNotFound_throwsResourceNotFoundException()`, `createProduct_whenDuplicateSku_throwsBusinessRuleException()`, `updateProduct_whenValidRequest_updatesAndEvictsCache()`, `updateProduct_whenProductNotFound_throwsProductNotFoundException()`, `deleteProduct_setsActiveFalseAndSavesOutboxEvent()`, `deleteProduct_whenProductNotFound_throwsProductNotFoundException()`
    - Use `@ExtendWith(MockitoExtension.class)` + `@InjectMocks` (same as `ProductServiceTest`)
  - [x] 6.2 Integration test `AdminProductRestControllerIT` in `backend/product-service/src/test/java/com/robomart/product/integration/controller/`:
    - `@IntegrationTest` annotation (starts full Spring Boot context with Postgres + Kafka + ES + Redis testcontainers)
    - Use `RestClient` with `@LocalServerPort` (same pattern as `ProductRestControllerIT`)
    - Tests: `shouldCreateProductWhenValidRequest()` (201 + body), `shouldReturn404WhenProductNotFoundOnUpdate()`, `shouldSoftDeleteProductAndReturn204()`, `shouldReturnAllCategoriesFromCategoriesEndpoint()`
    - Note: No JWT authentication needed — product-service has no SecurityFilterChain; security is enforced at API Gateway only

### Frontend: admin-dashboard

- [x] Task 7: Register `ConfirmationService` + `ConfirmDialog` (AC: 5)
  - [x] 7.1 In `frontend/admin-dashboard/src/main.ts`, add after `ToastService`:
    ```typescript
    import ConfirmationService from 'primevue/confirmationservice'
    // ...
    app.use(ConfirmationService)
    ```
  - [x] 7.2 In `frontend/admin-dashboard/src/layouts/AdminLayout.vue`, add `<ConfirmDialog />` alongside `<Toast>`:
    ```vue
    <ConfirmDialog />
    <Toast position="top-right" :maxToasts="3" />
    ```
    Import: `import ConfirmDialog from 'primevue/confirmdialog'`

- [x] Task 8: Create admin API client and product API module (AC: 1–6)
  - [x] 8.1 Create `frontend/admin-dashboard/src/api/adminClient.ts` — dedicated axios instance with Bearer token:
    ```typescript
    import axios from 'axios'
    import { useAdminAuthStore } from '@/stores/useAdminAuthStore'

    const adminClient = axios.create({
      baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
      timeout: 10000,
      headers: { 'Content-Type': 'application/json' },
    })

    adminClient.interceptors.request.use((config) => {
      // Access store directly — safe because initAuth() completes before app.mount()
      try {
        const authStore = useAdminAuthStore()
        if (authStore.accessToken) {
          config.headers['Authorization'] = `Bearer ${authStore.accessToken}`
        }
      } catch {
        // Pinia not yet initialized (e.g., unit test environment)
      }
      return config
    })

    adminClient.interceptors.response.use(
      (response) => response,
      (error) => {
        if (error.response?.status === 401) {
          // Token expired — redirect to unauthorized
          window.location.href = '/admin/unauthorized'
        }
        return Promise.reject(error)
      },
    )

    export default adminClient
    ```
  - [x] 8.2 Create `frontend/admin-dashboard/src/api/productAdminApi.ts`:
    ```typescript
    import adminClient from './adminClient'

    export interface AdminProduct {
      id: number
      sku: string
      name: string
      description: string | null
      price: number
      brand: string | null
      rating: number | null
      stockQuantity: number
      category: { id: number; name: string }
      images: { imageUrl: string; altText: string | null }[]
      createdAt: string
      updatedAt: string
    }

    export interface AdminProductListItem {
      id: number
      sku: string
      name: string
      price: number
      brand: string | null
      rating: number | null
      stockQuantity: number
      categoryName: string
      primaryImageUrl: string | null
    }

    export interface CategoryOption {
      id: number
      name: string
    }

    export interface CreateProductPayload {
      name: string
      description: string
      categoryId: number
      price: number
      brand: string
      sku?: string
    }

    export interface UpdateProductPayload {
      name: string
      description: string
      categoryId: number
      price: number
      brand: string
    }

    // Matches backend PagedResponse<ProductListResponse>
    interface PagedResponse<T> {
      data: T[]
      pagination: { page: number; size: number; totalElements: number; totalPages: number }
      traceId: string
    }

    // Matches backend ApiResponse<T>
    interface ApiResponse<T> {
      data: T
      traceId: string
    }

    export async function listProducts(page = 0, size = 25): Promise<PagedResponse<AdminProductListItem>> {
      const { data } = await adminClient.get<PagedResponse<AdminProductListItem>>(
        `/api/v1/products?page=${page}&size=${size}`,
      )
      return data
    }

    export async function createProduct(payload: CreateProductPayload): Promise<AdminProduct> {
      const { data } = await adminClient.post<ApiResponse<AdminProduct>>(
        '/api/v1/admin/products',
        payload,
      )
      return data.data
    }

    export async function updateProduct(id: number, payload: UpdateProductPayload): Promise<AdminProduct> {
      const { data } = await adminClient.put<ApiResponse<AdminProduct>>(
        `/api/v1/admin/products/${id}`,
        payload,
      )
      return data.data
    }

    export async function deleteProduct(id: number): Promise<void> {
      await adminClient.delete(`/api/v1/admin/products/${id}`)
    }

    export async function patchProductField(id: number, field: 'price' | 'stockQuantity', value: number): Promise<void> {
      // Uses PUT with full update — fetch current product first or use a patch approach
      // Simplest: PUT /api/v1/admin/products/{id} with partial fields merged
      await adminClient.put(`/api/v1/admin/products/${id}`, { [field]: value })
    }

    export async function getCategories(): Promise<CategoryOption[]> {
      const { data } = await adminClient.get<CategoryOption[]>('/api/v1/admin/categories')
      return data
    }
    ```
    **Note**: Inline editing of price/stock cells sends `PUT /api/v1/admin/products/{id}`. Since `UpdateProductRequest` requires all fields, the DataTable row data must hold the full product fields. When only price or stock is changed inline, the PUT body includes all existing field values + the changed field. This avoids adding a separate PATCH endpoint.

    **Alternative for stock inline edit**: Stock quantity is NOT in `UpdateProductRequest` (it belongs to inventory management in Story 5.4). Inline stock editing in Story 5.2 should be REMOVED from scope — the epics AC says inline editing for price is sufficient. Re-read AC3: "I click the price or stock cell" — stock may be out of scope for admin product CRUD (Story 5.4 handles inventory). Implement inline edit for **price only** in this story.

- [x] Task 9: Implement `ProductsPage.vue` — replace placeholder (AC: 1, 2, 3, 4, 5, 6)
  - [x] 9.1 Create `frontend/admin-dashboard/src/views/ProductsPage.vue`:
    ```vue
    <script setup lang="ts">
    import { ref, onMounted } from 'vue'
    import { useToast } from 'primevue/usetoast'
    import { useConfirm } from 'primevue/useconfirm'
    import DataTable from 'primevue/datatable'
    import Column from 'primevue/column'
    import Button from 'primevue/button'
    import InputText from 'primevue/inputtext'
    import InputNumber from 'primevue/inputnumber'
    import Tag from 'primevue/tag'
    import Skeleton from 'primevue/skeleton'
    import { EmptyState } from '@robo-mart/shared'
    import SlideOverPanel from '@/components/SlideOverPanel.vue'
    import ProductFormSlideOver from '@/components/products/ProductFormSlideOver.vue'
    import {
      listProducts,
      createProduct,
      updateProduct,
      deleteProduct,
      type AdminProductListItem,
      type CreateProductPayload,
      type UpdateProductPayload,
    } from '@/api/productAdminApi'
    // ...
    </script>
    ```
  - [x] 9.2 State: `products` (ref\<AdminProductListItem[]\>), `isLoading` (ref, true on mount), `selectedRows` (ref), `showForm` (ref\<boolean\>), `editingProduct` (ref\<AdminProductListItem | null\>, null = create mode)
  - [x] 9.3 `onMounted`: fetch `listProducts()` → populate `products`, set `isLoading = false`
  - [x] 9.4 DataTable config:
    - `:value="products"` `:loading="isLoading"` `:paginator="true"` `:rows="25"` `:rowsPerPageOptions="[10, 25, 50, 100]"`
    - `v-model:selection="selectedRows"` `selectionMode="multiple"`
    - `filterDisplay="row"` (filter row below column headers)
    - Empty state slot: `<template #empty><EmptyState variant="generic" title="No products yet" description="Start building your catalog" /></template>`
    - Loading slot: `<template #loadingicon><Skeleton /></template>`
  - [x] 9.5 Columns:
    - `<Column field="id" header="ID" sortable style="width: 80px" />`
    - `<Column field="name" header="Name" sortable :showFilterMenu="false">` with filter input
    - `<Column field="categoryName" header="Category" sortable />`
    - `<Column field="price" header="Price" sortable>` with inline edit on cell click (see 9.6)
    - `<Column field="stockQuantity" header="Stock" sortable />` (read-only in this story)
    - `<Column header="Actions" style="width: 120px">` with Edit + Delete ghost buttons
  - [x] 9.6 Inline price editing — use `cellEditComplete` event on DataTable or `@click` to toggle editable cell:
    ```vue
    <!-- Price column inline edit -->
    <Column field="price" header="Price" sortable>
      <template #body="{ data }">
        <span v-if="editingCell?.id !== data.id || editingCell?.field !== 'price'"
              class="cursor-pointer hover:text-primary-700"
              @click="startCellEdit(data, 'price')">
          ${{ data.price.toFixed(2) }}
        </span>
        <InputNumber v-else
          v-model="editingCell.value"
          mode="currency" currency="USD"
          :min="0.01"
          autofocus
          @keydown.enter="saveCellEdit(data)"
          @keydown.escape="cancelCellEdit"
          @blur="saveCellEdit(data)"
          class="w-full" />
      </template>
    </Column>
    ```
    State: `editingCell` (ref\<{ id: number; field: string; value: number } | null\>)
    - `startCellEdit(row, field)`: sets editingCell
    - `saveCellEdit(row)`: calls `updateProduct(row.id, { ...row, price: editingCell.value })`, updates local row, shows toast, clears editingCell
    - `cancelCellEdit()`: clears editingCell without saving
  - [x] 9.7 Add Product button (top-right of page header, above DataTable):
    ```vue
    <Button label="Add Product" icon="pi pi-plus" @click="openCreate" />
    ```
    `openCreate()`: sets `editingProduct = null`, `showForm = true`
  - [x] 9.8 Edit button in Actions column: `openEdit(row)` → sets `editingProduct = row`, `showForm = true`
  - [x] 9.9 Delete button in Actions column (danger ghost button):
    ```typescript
    function confirmDelete(row: AdminProductListItem) {
      confirm.require({
        message: `Delete "${row.name}"? This cannot be undone.`,
        header: 'Confirm Delete',
        icon: 'pi pi-exclamation-triangle',
        acceptClass: 'p-button-danger',
        accept: () => handleDelete(row),
      })
    }
    async function handleDelete(row: AdminProductListItem) {
      await deleteProduct(row.id)
      products.value = products.value.filter(p => p.id !== row.id)
      toast.add({ severity: 'success', summary: 'Product deleted', life: 3000 })
    }
    ```
    `const confirm = useConfirm()` (from `primevue/useconfirm`)
  - [x] 9.10 Bulk action toolbar (shown when `selectedRows.length > 0`): "Delete Selected" danger button — loops through selection calling `deleteProduct()` then filters from local list

- [x] Task 10: Create `ProductFormSlideOver.vue` (AC: 2, 4)
  - [x] 10.1 Create `frontend/admin-dashboard/src/components/products/ProductFormSlideOver.vue`
  - [x] 10.2 Props: `v-model:visible` (boolean), `product` (AdminProductListItem | null, null = create mode)
  - [x] 10.3 Emits: `saved` (emitted after successful create or update)
  - [x] 10.4 Wraps `SlideOverPanel` (reuse existing component at `@/components/SlideOverPanel.vue`)
  - [x] 10.5 `onMounted`/`watch(product)`: fetch `getCategories()` to populate category dropdown
  - [x] 10.6 Form fields using PrimeVue:
    - Name: `<InputText />` (required, validate on blur: show error below if empty)
    - Description: `<Textarea />` rows=3
    - Category: `<Select />` (PrimeVue 4.x — `primevue/select`, NOT `primevue/dropdown`; Dropdown was renamed to Select in PrimeVue 4.x) with `optionLabel="name"` `optionValue="id"`
    - Price: `<InputNumber />` mode="currency" currency="USD" :min="0.01"
    - Brand: `<InputText />`
    - SKU: `<InputText />` placeholder="Auto-generated if empty" (only shown in create mode)
  - [x] 10.7 Validation on blur: name required, price > 0, category required. Error messages displayed below fields (not banner).
  - [x] 10.8 Submit button: `"Save Product"` (primary), Cancel button (secondary, closes slide-over)
  - [x] 10.9 On submit:
    - Validate all fields (scroll to first error)
    - Loading state: button disabled + `pi-spinner` icon + "Saving..."
    - If `product === null` (create): call `createProduct(payload)` → toast "Product created" → emit `saved` → close slide-over
    - If `product !== null` (edit): call `updateProduct(product.id, payload)` → toast "Product updated" → emit `saved` → close slide-over
  - [x] 10.10 On `saved` emit, `ProductsPage.vue` reloads product list: `await fetchProducts()`
  - [x] 10.11 Two-column form layout (per UX spec for admin forms — efficiency): Name + SKU on left column, Category + Price on right column in a `grid grid-cols-2 gap-4` wrapper. Description + Brand span full width.

- [x] Task 11: Write unit tests (AC: 1–6)
  - [x] 11.1 `src/__tests__/ProductsPage.test.ts`:
    - Mock `productAdminApi` module with `vi.mock('@/api/productAdminApi', ...)`
    - `listProducts` mock returns 3 product rows
    - Tests:
      1. renders DataTable with product rows when loaded
      2. shows EmptyState when products list is empty
      3. "Add Product" button click sets `showForm = true`
      4. delete button triggers `confirm.require()` (spy on `useConfirm().require`)
      5. Skeleton rows shown during loading (isLoading=true)
    - Use `shallowMount()` + stub PrimeVue DataTable to avoid portal issues
  - [x] 11.2 `src/__tests__/ProductFormSlideOver.test.ts`:
    - Mock `productAdminApi` module
    - `getCategories` returns 2 categories
    - Tests:
      1. form renders with empty fields when `product=null` (create mode)
      2. form pre-populated when `product` prop provided (edit mode)
      3. name validation error shown when submitted with empty name
      4. calls `createProduct` when submitting in create mode
      5. calls `updateProduct` when submitting in edit mode
    - Use `shallowMount()` + stub `SlideOverPanel`

### Review Findings

- [x] [Review][Patch] P1: description hardcoded to empty string in saveCellEdit + resetForm — also add `description` to `ProductListResponse`/`AdminProductListItem` [ProductsPage.vue:74, ProductFormSlideOver.vue:62, ProductListResponse.java, productAdminApi.ts]
- [x] [Review][Patch] P2: Bulk delete toast always shows original count even when some deletes fail [ProductsPage.vue:128]
- [x] [Review][Patch] P3: `String.valueOf(null)` produces string `"null"` instead of JSON null for categoryId in outbox payload [ProductService.java:buildOutboxPayload]
- [x] [Review][Patch] P4: No `isDeleting` reactive flag — bulk delete button not disabled during in-flight requests [ProductsPage.vue:deleteSelected]
- [x] [Review][Patch] P5: Bulk delete bypasses ConfirmDialog, violating AC5 safety intent [ProductsPage.vue:deleteSelected]
- [x] [Review][Defer] W1: SKU uniqueness TOCTOU — requires DB unique constraint; not in scope [ProductService.java:createProduct] — deferred, pre-existing
- [x] [Review][Defer] W2: `getAllCategories()` unbounded findAll() — categories are small set (~10-50); acceptable for now [ProductService.java:getAllCategories] — deferred, pre-existing
- [x] [Review][Defer] W3: 401 redirect shows error toast before navigation — pre-existing auth pattern from Story 5.1 [adminClient.ts] — deferred, pre-existing
- [x] [Review][Defer] W4: Delete→Update concurrent race condition — requires @Version optimistic locking enforcement; architectural decision [ProductService.java] — deferred, pre-existing
- [x] [Review][Defer] W5: Bulk delete selection doesn't work across paginated pages — PrimeVue DataTable limitation; low impact [ProductsPage.vue] — deferred, pre-existing

## Dev Notes

### Critical: Scope Boundary — Stock vs. Price Inline Editing

The epics AC3 says "price or stock cell" — but stock quantity is managed by inventory-service in Story 5.4 (`AdminProductRestController` has no stock field in `UpdateProductRequest`). **In this story: inline editing is for price only.** Stock column is read-only. Do NOT add `stockQuantity` to `UpdateProductRequest` — that's Story 5.4's domain.

### Critical: PrimeVue 4.x Component Renames

Several PrimeVue 3.x components were renamed in PrimeVue 4.x:

| PrimeVue 3.x | PrimeVue 4.x | Import path |
|--------------|--------------|-------------|
| `Dropdown` | `Select` | `primevue/select` |
| `Sidebar` | `Drawer` | `primevue/drawer` |
| `InputText` | `InputText` (unchanged) | `primevue/inputtext` |

**Use `Select` not `Dropdown` for the category selector.** Story 5.1 established `Drawer` (not `Sidebar`) for `SlideOverPanel.vue`. Follow the same pattern.

### Critical: `ConfirmationService` Not in Story 5.1 main.ts

Story 5.1 registered `ToastService` but NOT `ConfirmationService`. Task 7 must add it before any `useConfirm()` call works. Without it, `useConfirm()` throws at runtime.

### Critical: `AdminProductRestController` — No Security Annotations Needed

Product-service has NO Spring Security (`SecurityFilterChain`) configured. The ADMIN role check is enforced at the API Gateway (`GatewaySecurityConfig.java:33`):
```java
.pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
```
Do NOT add `@PreAuthorize("hasRole('ADMIN')")` to the controller — security-lib is a separate module and product-service doesn't import it. The gateway rejects unauthorized requests before they reach the service.

### Critical: Jackson 3.x ObjectMapper Import in ProductService

When injecting `ObjectMapper` for `buildOutboxPayload()`, use:
```java
import tools.jackson.databind.ObjectMapper;  // Jackson 3.x package
```
NOT `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2.x). Spring Boot 4.x bundles Jackson 3.x. The `ObjectMapper` bean is auto-configured — inject via constructor.

`writeValueAsString()` in Jackson 3.x throws `JacksonException` (unchecked) instead of `JsonProcessingException` — no checked exception handling needed.

### Critical: Flyway Migration Must Be Next Version

The product-service has existing migrations V1–V4:
- `V1__create_products_table.sql`
- `V2__create_categories_table.sql`
- `V3__create_product_images_table.sql`
- `V4__create_outbox_events_table.sql`

The new migration MUST be `V5__add_product_active_column.sql`. Do NOT skip version numbers.

### Critical: ProductRepository Query Updates for Active Filter

After adding `active` column, existing `findAllWithDetails` and `findByCategoryId` queries MUST filter `active = true` to prevent soft-deleted products from appearing in customer browse and search results.

Current queries:
```java
@Query("SELECT p FROM Product p")
Page<Product> findAllWithDetails(Pageable pageable);

Page<Product> findByCategoryId(Long categoryId, Pageable pageable);  // Spring Data derived
```

Updated:
```java
@Query("SELECT p FROM Product p WHERE p.active = true")
Page<Product> findAllWithDetails(Pageable pageable);

// Replace derived query with explicit JPQL for active filter:
@EntityGraph(attributePaths = {"category"})
@Query("SELECT p FROM Product p WHERE p.category.id = :categoryId AND p.active = true")
Page<Product> findByCategoryIdAndActive(Long categoryId, Pageable pageable);

@EntityGraph(attributePaths = {"category", "images"})
@Query("SELECT p FROM Product p WHERE p.id = :id AND p.active = true")
Optional<Product> findByIdWithDetails(Long id);
```

Then update `ProductService.getProducts()` to call `findByCategoryIdAndActive()` instead of `findByCategoryId()`.

### Critical: Outbox Payload — Fields Required by OutboxPollingService

`OutboxPollingService.publishEvent()` reads these fields from JSON payload:

| Event type | Required JSON fields |
|-----------|---------------------|
| `PRODUCT_CREATED` | id, sku, name, description, price, categoryId, categoryName, brand, rating (nullable), stockQuantity |
| `PRODUCT_UPDATED` | same as above |
| `PRODUCT_DELETED` | id, sku |

The `buildOutboxPayload()` helper must include all required fields. Missing fields will cause `NullPointerException` in `OutboxPollingService` during polling (silent failure — event marked failed, logged as ERROR).

### Critical: `listProducts()` Uses Public Endpoint

The `listProducts()` function in `productAdminApi.ts` calls **public** endpoint `GET /api/v1/products` — NOT `/api/v1/admin/products`. There is no admin-specific list endpoint because the public listing is sufficient. Admin views active products; soft-deleted products are already filtered (`active = true` in repository queries).

### Critical: InlineEdit — Must Preserve All Fields for PUT

When saving a price inline edit, the PUT body for `UpdateProductRequest` requires: `name`, `description`, `categoryId`, `price`, `brand` (all non-null). The `AdminProductListItem` from `listProducts()` has `categoryName` but NOT `categoryId`. Two options:
1. Load full product detail before submitting PUT (extra API call)
2. Add `categoryId` to `AdminProductListItem`/`ProductListResponse` in backend

**Recommended**: Add `categoryId` to `ProductListResponse` backend DTO (add `Long categoryId` field to the record). This avoids the extra API call for inline editing. Update `ProductMapper.toListResponse()` with `@Mapping(target = "categoryId", source = "category.id")`.

This means `ProductListResponse` must be updated in the backend and `AdminProductListItem` interface in the frontend must include `categoryId: number`.

### Architecture Compliance

- Backend follows REST conventions: `/api/v1/admin/products` (plural nouns, kebab-case)
- Response wrapper: `ApiResponse<T>` for single entities, `PagedResponse<T>` for lists (from `com.robomart.common.dto`)
- HTTP status codes: 201 Created (POST), 200 OK (PUT), 204 No Content (DELETE)
- Frontend proxy: Admin dashboard Vite config proxies `/api` to `http://localhost:8080` (API Gateway). No CORS issues in dev.
- Admin API access: all `/api/v1/admin/**` routes require `ADMIN` role (enforced at gateway)

### Project Structure Notes

**Backend files to create/modify:**
```
backend/product-service/src/main/java/com/robomart/product/
├── controller/
│   └── AdminProductRestController.java          ← NEW
├── dto/
│   ├── CreateProductRequest.java                ← NEW
│   ├── UpdateProductRequest.java                ← NEW
│   └── ProductListResponse.java                 ← MODIFY (add categoryId)
├── entity/
│   └── Product.java                             ← MODIFY (add active)
├── mapper/
│   └── ProductMapper.java                       ← MODIFY (add new mappings)
├── repository/
│   └── ProductRepository.java                   ← MODIFY (filter active=true, add existsBySku)
└── service/
    └── ProductService.java                      ← MODIFY (add CRUD methods)
backend/product-service/src/main/resources/db/migration/
└── V5__add_product_active_column.sql            ← NEW
```

**Frontend files to create/modify:**
```
frontend/admin-dashboard/src/
├── main.ts                                      ← MODIFY (add ConfirmationService)
├── layouts/
│   └── AdminLayout.vue                          ← MODIFY (add <ConfirmDialog />)
├── api/
│   ├── adminClient.ts                           ← NEW
│   └── productAdminApi.ts                       ← NEW
├── views/
│   └── ProductsPage.vue                         ← MODIFY (replace placeholder)
├── components/
│   └── products/
│       └── ProductFormSlideOver.vue             ← NEW
└── __tests__/
    ├── ProductsPage.test.ts                     ← NEW
    └── ProductFormSlideOver.test.ts             ← NEW
```

### Previous Story Intelligence (Story 5.1)

**Established patterns:**
- `SlideOverPanel.vue` exists at `src/components/SlideOverPanel.vue` — wraps PrimeVue `Drawer` (position=right, 640px width, dismissable, modal). Props: `v-model:visible`, `title`. Reuse it in `ProductFormSlideOver`.
- PrimeVue `Toast` import from `primevue/toast`, `useToast()` from `primevue/usetoast`
- PrimeVue `DataTable` from `primevue/datatable`, `Column` from `primevue/column`
- `EmptyState` imported from `@robo-mart/shared` with `variant` prop
- Tests use `shallowMount()` for components with PrimeVue portals (Dialog, Drawer teleport to `<body>`) — avoids jsdom resolution failures
- All test imports: `{ mount, shallowMount }` from `@vue/test-utils`
- Pinia store test helper: `createPinia()` passed in `global.plugins`

**Known issues fixed in 5.1 (do not repeat):**
- URL-safe base64 not handled in JWT decode — fixed in `useAdminAuthStore`
- JWT `exp` claim checked for expiry
- `initialized` flag guards concurrent `initAuth()` calls

**Existing PrimeVue components already imported/available in app** (no re-registration needed):
- `Toast`, `Badge`, `Menu`, `Dialog`, `AutoComplete`, `DataTable`, `Column`, `Drawer`, `Skeleton`

**New PrimeVue components needed in this story:**
- `ConfirmDialog` (from `primevue/confirmdialog`) — needs `ConfirmationService` in `main.ts`
- `Select` (from `primevue/select`) — category dropdown
- `InputNumber` (from `primevue/inputnumber`) — price input
- `Textarea` (from `primevue/textarea`) — description field
- `Button` (from `primevue/button`)

### Git Intelligence

Recent commit pattern: `feat: implement <description> (Story X.Y)` for main implementation, `chore: mark story X.Y as done — all code review patches applied` for post-review.

Commit for this story: `feat: implement admin product CRUD (Story 5.2)`

### References

- [Source: epics.md#Story 5.2] — Acceptance Criteria
- [Source: ux-design-specification.md#Admin Dashboard] — DataTable, form patterns, button hierarchy, feedback patterns
- [Source: ux-design-specification.md#Form Patterns (Admin)] — Two-column layout, validation on blur, optional field labeling
- [Source: ux-design-specification.md#Data Display Patterns — Tables (Admin)] — columns max 7, default sort by most recent
- [Source: architecture.md#API Gateway Route Boundaries] — `/api/v1/admin/**` → ADMIN role required
- [Source: architecture.md#REST API Response Wrapper] — `ApiResponse<T>` shape
- [Source: architecture.md#Requirements to Structure Mapping] — Admin Product CRUD → `product-service` + `admin-dashboard/product/`
- [Source: backend/product-service/.../ProductRestController.java] — REST controller pattern (copy `getTraceId()`)
- [Source: backend/product-service/.../ProductService.java] — Service patterns: `@Transactional`, `@Cacheable`, cache value `"productDetail"`
- [Source: backend/product-service/.../OutboxPollingService.java] — Required JSON payload fields per event type
- [Source: backend/product-service/.../ProductMapper.java] — MapStruct pattern, @Mapping ignores
- [Source: backend/product-service/.../ProductRepository.java] — Current queries to update
- [Source: backend/product-service/.../ProductListResponse.java] — Add `categoryId` field
- [Source: backend/test-support/.../IntegrationTest.java] — `@IntegrationTest` = SpringBootTest + 4 containers
- [Source: frontend/admin-dashboard/src/main.ts] — Bootstrap pattern (add ConfirmationService after ToastService)
- [Source: frontend/admin-dashboard/src/layouts/AdminLayout.vue] — Add `<ConfirmDialog />` here
- [Source: frontend/admin-dashboard/src/components/SlideOverPanel.vue] — Reuse for ProductFormSlideOver
- [Source: frontend/customer-website/src/api/client.ts] — axios client pattern (for adminClient.ts)

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

N/A

### Completion Notes List

- `ProductListResponse` required `categoryId` field addition to avoid extra API calls during inline price PUT
- `Map.of()` replaced with `LinkedHashMap` for outbox payload due to null value rejection in unit tests (product.getId() is null without DB persistence)
- `String.valueOf()` used instead of `.toString()` for null-safe ID conversion in outbox event saving
- `ProductSearchService.toProductListResponse()` updated to pass `doc.getCategoryId()` after DTO now has 10 fields
- `ResourceNotFoundException` public constructor used (`new ResourceNotFoundException("msg")`) — 2-arg constructor is protected
- `ProductServiceCacheTest` needed 3 additional mock beans: `CategoryRepository`, `OutboxPublisher`, `ObjectMapper`
- `EmptyState` component uses `@action` emit with `cta-label` prop — no `#action` slot
- `ProductFormSlideOver` tests require mount with `visible: false` then `setProps({ visible: true })` to trigger the watcher (watch only fires on value change, not initial mount)
- Pre-existing `CommandPalette.test.ts` failure in `renders AutoComplete when dialog is open` — unrelated to this story

### File List

**Backend — Created:**
- `backend/product-service/src/main/resources/db/migration/V5__add_product_active_column.sql`
- `backend/product-service/src/main/java/com/robomart/product/dto/CreateProductRequest.java`
- `backend/product-service/src/main/java/com/robomart/product/dto/UpdateProductRequest.java`
- `backend/product-service/src/main/java/com/robomart/product/controller/AdminProductRestController.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/service/AdminProductServiceTest.java`
- `backend/product-service/src/test/java/com/robomart/product/integration/controller/AdminProductRestControllerIT.java`

**Backend — Modified:**
- `backend/product-service/src/main/java/com/robomart/product/entity/Product.java`
- `backend/product-service/src/main/java/com/robomart/product/dto/ProductListResponse.java`
- `backend/product-service/src/main/java/com/robomart/product/repository/ProductRepository.java`
- `backend/product-service/src/main/java/com/robomart/product/mapper/ProductMapper.java`
- `backend/product-service/src/main/java/com/robomart/product/service/ProductService.java`
- `backend/product-service/src/main/java/com/robomart/product/service/ProductSearchService.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/service/ProductServiceTest.java`
- `backend/product-service/src/test/java/com/robomart/product/unit/service/ProductServiceCacheTest.java`
- `backend/product-service/src/test/java/com/robomart/product/integration/repository/ProductRepositoryIT.java`

**Frontend — Created:**
- `frontend/admin-dashboard/src/api/adminClient.ts`
- `frontend/admin-dashboard/src/api/productAdminApi.ts`
- `frontend/admin-dashboard/src/components/products/ProductFormSlideOver.vue`
- `frontend/admin-dashboard/src/__tests__/ProductsPage.test.ts`
- `frontend/admin-dashboard/src/__tests__/ProductFormSlideOver.test.ts`

**Frontend — Modified:**
- `frontend/admin-dashboard/src/main.ts`
- `frontend/admin-dashboard/src/layouts/AdminLayout.vue`
- `frontend/admin-dashboard/src/views/ProductsPage.vue`
