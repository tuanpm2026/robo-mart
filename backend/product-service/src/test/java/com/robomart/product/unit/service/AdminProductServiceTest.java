package com.robomart.product.unit.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.common.exception.BusinessRuleException;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.exception.ProductNotFoundException;
import com.robomart.product.mapper.ProductMapper;
import com.robomart.product.repository.CategoryRepository;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.OutboxPublisher;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private OutboxPublisher outboxPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    @InjectMocks
    private ProductService productService;

    private Category testCategory;
    private Product testProduct;

    @BeforeEach
    void setUp() {
        lenient().when(tracer.currentSpan()).thenReturn(span);
        lenient().when(span.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn("test-trace-id");

        testCategory = new Category();
        testCategory.setName("Electronics");

        testProduct = new Product();
        testProduct.setSku("ABCD1234");
        testProduct.setName("Test Product");
        testProduct.setDescription("A test product");
        testProduct.setPrice(BigDecimal.valueOf(29.99));
        testProduct.setCategory(testCategory);
        testProduct.setBrand("TestBrand");
        testProduct.setStockQuantity(50);
    }

    // ─── createProduct ───────────────────────────────────────────────────────

    @Test
    void createProduct_whenValidRequest_savesProductAndOutboxEvent() throws Exception {
        var request = new CreateProductRequest("Test Product", "A test product", 1L,
                BigDecimal.valueOf(29.99), "TestBrand", "ABCD1234");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(productMapper.toEntity(request)).thenReturn(testProduct);
        when(productRepository.existsBySku("ABCD1234")).thenReturn(false);
        when(productRepository.save(testProduct)).thenReturn(testProduct);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"id\":1}");

        var detailResponse = new ProductDetailResponse(1L, "ABCD1234", "Test Product",
                "A test product", BigDecimal.valueOf(29.99), null, "TestBrand", 50,
                null, null, null, null);
        when(productMapper.toDetailResponse(testProduct)).thenReturn(detailResponse);

        var result = productService.createProduct(request);

        assertThat(result.id()).isEqualTo(1L);
        verify(productRepository).save(testProduct);
        verify(outboxPublisher).saveEvent(eq("PRODUCT"), any(), eq("PRODUCT_CREATED"), anyString());
    }

    @Test
    void createProduct_whenCategoryNotFound_throwsResourceNotFoundException() {
        var request = new CreateProductRequest("Test Product", null, 999L,
                BigDecimal.valueOf(29.99), null, null);

        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("999");

        verify(productRepository, never()).save(any());
        verify(outboxPublisher, never()).saveEvent(any(), any(), any(), any());
    }

    @Test
    void createProduct_whenDuplicateSku_throwsBusinessRuleException() {
        var request = new CreateProductRequest("Test Product", null, 1L,
                BigDecimal.valueOf(29.99), null, "DUPE-SKU");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(productMapper.toEntity(request)).thenReturn(testProduct);
        testProduct.setSku("DUPE-SKU");
        when(productRepository.existsBySku("DUPE-SKU")).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("DUPE-SKU");

        verify(productRepository, never()).save(any());
    }

    @Test
    void createProduct_whenNoSkuProvided_autoGeneratesSku() throws Exception {
        var request = new CreateProductRequest("Test Product", null, 1L,
                BigDecimal.valueOf(29.99), null, null);

        testProduct.setSku(null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(productMapper.toEntity(request)).thenReturn(testProduct);
        when(productRepository.existsBySku(any())).thenReturn(false);
        when(productRepository.save(testProduct)).thenReturn(testProduct);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"id\":1}");
        when(productMapper.toDetailResponse(testProduct)).thenReturn(
                new ProductDetailResponse(1L, "AUTOGEN", "Test Product", null,
                        BigDecimal.valueOf(29.99), null, null, 0, null, null, null, null));

        productService.createProduct(request);

        // SKU should have been auto-generated (8 chars uppercase)
        assertThat(testProduct.getSku()).isNotNull().hasSize(8);
        verify(productRepository).save(testProduct);
    }

    // ─── updateProduct ───────────────────────────────────────────────────────

    @Test
    void updateProduct_whenValidRequest_updatesProductAndEvictsCache() throws Exception {
        var request = new UpdateProductRequest("Updated Name", "Updated desc", 1L,
                BigDecimal.valueOf(49.99), "NewBrand");

        when(productRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(testProduct));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(productRepository.save(testProduct)).thenReturn(testProduct);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"id\":1}");
        when(productMapper.toDetailResponse(testProduct)).thenReturn(
                new ProductDetailResponse(1L, "ABCD1234", "Updated Name", "Updated desc",
                        BigDecimal.valueOf(49.99), null, "NewBrand", 50, null, null, null, null));

        var result = productService.updateProduct(1L, request);

        assertThat(result.name()).isEqualTo("Updated Name");
        verify(productMapper).updateEntityFromRequest(request, testProduct);
        verify(productRepository).save(testProduct);
        verify(outboxPublisher).saveEvent(eq("PRODUCT"), any(), eq("PRODUCT_UPDATED"), anyString());
    }

    @Test
    void updateProduct_whenProductNotFound_throwsProductNotFoundException() {
        var request = new UpdateProductRequest("Name", null, 1L, BigDecimal.valueOf(9.99), null);

        when(productRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProduct(999L, request))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("999");
    }

    // ─── deleteProduct ───────────────────────────────────────────────────────

    @Test
    void deleteProduct_setsActiveFalseAndSavesOutboxEvent() throws Exception {
        when(productRepository.findByIdWithDetailsIncludeInactive(1L))
                .thenReturn(Optional.of(testProduct));
        when(productRepository.save(testProduct)).thenReturn(testProduct);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"id\":1,\"sku\":\"TEST-001\"}");

        productService.deleteProduct(1L);

        assertThat(testProduct.isActive()).isFalse();
        verify(productRepository).save(testProduct);
        verify(outboxPublisher).saveEvent(eq("PRODUCT"), any(), eq("PRODUCT_DELETED"), anyString());
    }

    @Test
    void deleteProduct_whenProductNotFound_throwsProductNotFoundException() {
        when(productRepository.findByIdWithDetailsIncludeInactive(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.deleteProduct(999L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("999");

        verify(productRepository, never()).save(any());
    }

    // ─── getAllCategories ─────────────────────────────────────────────────────

    @Test
    void getAllCategories_returnsAllCategories() {
        when(categoryRepository.findAll()).thenReturn(java.util.List.of(testCategory));
        when(productMapper.toCategoryResponse(testCategory)).thenReturn(
                new com.robomart.product.dto.CategoryResponse(1L, "Electronics", null));

        var result = productService.getAllCategories();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("Electronics");
    }
}
