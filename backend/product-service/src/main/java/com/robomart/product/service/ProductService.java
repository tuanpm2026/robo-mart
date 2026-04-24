package com.robomart.product.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.common.audit.AuditAction;
import com.robomart.common.audit.Auditable;
import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.common.exception.BusinessRuleException;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.product.dto.CategoryResponse;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.exception.ProductNotFoundException;
import com.robomart.product.mapper.ProductMapper;
import com.robomart.product.repository.CategoryRepository;
import com.robomart.product.repository.ProductRepository;

import io.micrometer.tracing.Tracer;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          ProductMapper productMapper,
                          OutboxPublisher outboxPublisher,
                          ObjectMapper objectMapper,
                          Tracer tracer) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productMapper = productMapper;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    public PagedResponse<ProductListResponse> getProducts(Long categoryId, Pageable pageable) {
        Pageable clampedPageable = clampPageSize(pageable);

        Page<Product> page;
        if (categoryId != null) {
            log.debug("Fetching products for categoryId={}, page={}", categoryId, clampedPageable);
            page = productRepository.findByCategoryIdAndActive(categoryId, clampedPageable);
        } else {
            log.debug("Fetching all products, page={}", clampedPageable);
            page = productRepository.findAllWithDetails(clampedPageable);
        }

        var products = productMapper.toListResponse(page.getContent());
        var pagination = new PaginationMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );

        return new PagedResponse<>(products, pagination, getTraceId());
    }

    @Cacheable(value = "productDetail", key = "#productId")
    public ProductDetailResponse getProductById(Long productId) {
        log.debug("Fetching product detail for id={}", productId);

        Product product = productRepository.findByIdWithDetails(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        return productMapper.toDetailResponse(product);
    }

    @Auditable(action = AuditAction.CREATE, entityType = "Product", entityIdExpression = "#result?.id?.toString()")
    @Transactional
    public ProductDetailResponse createProduct(CreateProductRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException(
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
        outboxPublisher.saveEvent("PRODUCT", String.valueOf(saved.getId()), "PRODUCT_CREATED", payload);

        log.info("Product created: id={}, sku={}", saved.getId(), saved.getSku());
        return productMapper.toDetailResponse(saved);
    }

    @Auditable(action = AuditAction.UPDATE, entityType = "Product", entityIdExpression = "#result?.id?.toString()")
    @Transactional
    @CacheEvict(value = "productDetail", key = "#productId")
    public ProductDetailResponse updateProduct(Long productId, UpdateProductRequest request) {
        Product product = productRepository.findByIdWithDetails(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category not found: " + request.categoryId()));

        productMapper.updateEntityFromRequest(request, product);
        product.setCategory(category);

        Product saved = productRepository.save(product);

        String payload = buildOutboxPayload(saved);
        outboxPublisher.saveEvent("PRODUCT", String.valueOf(saved.getId()), "PRODUCT_UPDATED", payload);

        log.info("Product updated: id={}", saved.getId());
        return productMapper.toDetailResponse(saved);
    }

    @Auditable(action = AuditAction.DELETE, entityType = "Product", entityIdExpression = "#productId?.toString()")
    @Transactional
    @CacheEvict(value = "productDetail", key = "#productId")
    public void deleteProduct(Long productId) {
        Product product = productRepository.findByIdWithDetailsIncludeInactive(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        product.setActive(false);
        productRepository.save(product);

        // Minimal payload: id + sku required by OutboxPollingService PRODUCT_DELETED handler
        try {
            Map<String, Object> deletePayload = new LinkedHashMap<>();
            deletePayload.put("id", product.getId());
            deletePayload.put("sku", product.getSku());
            String payload = objectMapper.writeValueAsString(deletePayload);
            outboxPublisher.saveEvent("PRODUCT", String.valueOf(product.getId()), "PRODUCT_DELETED", payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize delete payload for product id=" + product.getId(), e);
        }

        log.info("Product soft-deleted: id={}", productId);
    }

    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().stream()
                .map(productMapper::toCategoryResponse)
                .toList();
    }

    private String buildOutboxPayload(Product product) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", product.getId());
            map.put("sku", product.getSku());
            map.put("name", product.getName());
            map.put("description", product.getDescription() != null ? product.getDescription() : "");
            map.put("price", product.getPrice());
            map.put("categoryId", product.getCategory().getId());
            map.put("categoryName", product.getCategory().getName());
            map.put("brand", product.getBrand() != null ? product.getBrand() : "");
            map.put("stockQuantity", product.getStockQuantity());
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload for product id=" + product.getId(), e);
        }
    }

    private Pageable clampPageSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        if (span != null) {
            var context = span.context();
            if (context != null) {
                return context.traceId();
            }
        }
        return "no-trace";
    }
}
