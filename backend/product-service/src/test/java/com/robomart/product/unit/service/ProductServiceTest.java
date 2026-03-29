package com.robomart.product.unit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.exception.ProductNotFoundException;
import com.robomart.product.mapper.ProductMapper;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    @InjectMocks
    private ProductService productService;

    @BeforeEach
    void setUp() {
        lenient().when(tracer.currentSpan()).thenReturn(span);
        lenient().when(span.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn("test-trace-id");
    }

    @Test
    void shouldReturnPagedProductsWhenNoCategoryFilter() {
        var product = createProduct();
        Page<Product> page = new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1);
        var listResponse = new ProductListResponse(1L, "TEST-001", "Test Product",
                BigDecimal.valueOf(29.99), BigDecimal.valueOf(4.5), "TestBrand", 100,
                "Electronics", "https://images.robomart.com/test.jpg");

        when(productRepository.findAllWithDetails(any(Pageable.class))).thenReturn(page);
        when(productMapper.toListResponse(page.getContent())).thenReturn(List.of(listResponse));

        var result = productService.getProducts(null, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(1);
        assertThat(result.pagination().totalElements()).isEqualTo(1);
        assertThat(result.pagination().page()).isZero();
        assertThat(result.traceId()).isEqualTo("test-trace-id");
    }

    @Test
    void shouldReturnFilteredProductsWhenCategoryIdProvided() {
        var product = createProduct();
        Page<Product> page = new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1);
        var listResponse = new ProductListResponse(1L, "TEST-001", "Test Product",
                BigDecimal.valueOf(29.99), BigDecimal.valueOf(4.5), "TestBrand", 100,
                "Electronics", null);

        when(productRepository.findByCategoryId(eq(1L), any(Pageable.class))).thenReturn(page);
        when(productMapper.toListResponse(page.getContent())).thenReturn(List.of(listResponse));

        var result = productService.getProducts(1L, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(1);
        verify(productRepository).findByCategoryId(eq(1L), any(Pageable.class));
    }

    @Test
    void shouldClampPageSizeWhenExceedsMax() {
        Page<Product> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);

        when(productRepository.findAllWithDetails(any(Pageable.class))).thenReturn(emptyPage);
        when(productMapper.toListResponse(anyList())).thenReturn(List.of());

        productService.getProducts(null, PageRequest.of(0, 200));

        verify(productRepository).findAllWithDetails(PageRequest.of(0, 100));
    }

    @Test
    void shouldReturnProductDetailWhenValidId() {
        var product = createProduct();
        var detailResponse = new ProductDetailResponse(1L, "TEST-001", "Test Product",
                "Description", BigDecimal.valueOf(29.99), BigDecimal.valueOf(4.5),
                "TestBrand", 100, null, List.of(), null, null);

        when(productRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(product));
        when(productMapper.toDetailResponse(product)).thenReturn(detailResponse);

        var result = productService.getProductById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Test Product");
    }

    @Test
    void shouldThrowProductNotFoundExceptionWhenInvalidId() {
        when(productRepository.findByIdWithDetails(99999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductById(99999L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("99999");
    }

    private Product createProduct() {
        var category = new Category();
        category.setName("Electronics");

        var product = new Product();
        product.setSku("TEST-001");
        product.setName("Test Product");
        product.setDescription("Description");
        product.setPrice(BigDecimal.valueOf(29.99));
        product.setCategory(category);
        product.setRating(BigDecimal.valueOf(4.50));
        product.setBrand("TestBrand");
        product.setStockQuantity(100);

        return product;
    }
}
