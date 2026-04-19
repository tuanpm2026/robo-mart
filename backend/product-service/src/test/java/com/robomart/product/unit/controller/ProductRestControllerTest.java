package com.robomart.product.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.common.dto.PagedResponse;
import com.robomart.product.controller.ProductRestController;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.dto.ProductSearchRequest;
import com.robomart.product.service.ProductSearchService;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductRestControllerTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProductSearchService productSearchService;

    @Mock
    private Tracer tracer;

    private ProductRestController controller;

    @BeforeEach
    void setUp() {
        controller = new ProductRestController(productService, productSearchService, tracer);
        when(tracer.currentSpan()).thenReturn(null);
    }

    @Test
    void shouldReturnProductsPageWhenGetProductsCalled() {
        Pageable pageable = PageRequest.of(0, 20);
        PagedResponse<ProductListResponse> pagedResponse = new PagedResponse<>(
                List.of(buildProductListResponse(1L, "Widget")),
                new com.robomart.common.dto.PaginationMeta(0, 20, 1L, 1),
                null);
        when(productService.getProducts(isNull(), any(Pageable.class))).thenReturn(pagedResponse);

        ResponseEntity<?> response = controller.getProducts(null, pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(productService).getProducts(isNull(), any(Pageable.class));
    }

    @Test
    void shouldFilterByCategoryWhenCategoryIdProvided() {
        Pageable pageable = PageRequest.of(0, 20);
        PagedResponse<ProductListResponse> pagedResponse = new PagedResponse<>(
                List.of(), new com.robomart.common.dto.PaginationMeta(0, 20, 0L, 0), null);
        when(productService.getProducts(eq(5L), any(Pageable.class))).thenReturn(pagedResponse);

        ResponseEntity<?> response = controller.getProducts(5L, pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(productService).getProducts(eq(5L), any(Pageable.class));
    }

    @Test
    void shouldReturnSearchResultsWhenSearchCalled() {
        Pageable pageable = PageRequest.of(0, 20);
        ProductSearchRequest searchRequest = new ProductSearchRequest("widget", null, null, null, null, null);
        PagedResponse<ProductListResponse> pagedResponse = new PagedResponse<>(
                List.of(), new com.robomart.common.dto.PaginationMeta(0, 20, 0L, 0), null);
        when(productSearchService.search(any(ProductSearchRequest.class), any(Pageable.class)))
                .thenReturn(pagedResponse);

        ResponseEntity<?> response = controller.searchProducts(searchRequest, pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(productSearchService).search(any(), any());
    }

    @Test
    void shouldReturnProductDetailWhenGetByIdCalled() {
        ProductDetailResponse detail = buildProductDetail(1L, "Widget");
        when(productService.getProductById(1L)).thenReturn(detail);

        ResponseEntity<?> response = controller.getProductById(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    private ProductListResponse buildProductListResponse(Long id, String name) {
        return new ProductListResponse(id, "SKU-001", name, "desc", new BigDecimal("19.99"),
                null, "Brand", 100, 1L, "Electronics", "http://example.com/img.jpg");
    }

    private ProductDetailResponse buildProductDetail(Long id, String name) {
        return new ProductDetailResponse(id, "SKU-001", name, "desc", new BigDecimal("19.99"),
                null, "Brand", 100, new com.robomart.product.dto.CategoryResponse(1L, "Electronics", null),
                List.of(), null, null);
    }
}
