package com.robomart.product.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.product.controller.AdminProductRestController;
import com.robomart.product.dto.CategoryResponse;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.service.ProductImageService;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminProductRestControllerTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProductImageService productImageService;

    @Mock
    private Tracer tracer;

    private AdminProductRestController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminProductRestController(productService, productImageService, tracer);
        when(tracer.currentSpan()).thenReturn(null);
    }

    @Test
    void shouldCreateProductAndReturn201() {
        CreateProductRequest request = new CreateProductRequest("Widget", "A widget", 1L, new BigDecimal("29.99"), "Brand", "SKU-001");
        ProductDetailResponse detail = buildDetail(1L, "Widget");
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(detail);

        ResponseEntity<?> response = controller.createProduct(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        verify(productService).createProduct(any());
    }

    @Test
    void shouldUpdateProductAndReturn200() {
        UpdateProductRequest request = new UpdateProductRequest("Updated Widget", "New desc", 1L, new BigDecimal("39.99"), "Brand");
        ProductDetailResponse detail = buildDetail(1L, "Updated Widget");
        when(productService.updateProduct(eq(1L), any(UpdateProductRequest.class))).thenReturn(detail);

        ResponseEntity<?> response = controller.updateProduct(1L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(productService).updateProduct(eq(1L), any());
    }

    @Test
    void shouldDeleteProductAndReturn204() {
        doNothing().when(productService).deleteProduct(1L);

        ResponseEntity<?> response = controller.deleteProduct(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(productService).deleteProduct(1L);
    }

    @Test
    void shouldReturnAllCategories() {
        List<CategoryResponse> categories = List.of(
                new CategoryResponse(1L, "Electronics", null),
                new CategoryResponse(2L, "Clothing", null)
        );
        when(productService.getAllCategories()).thenReturn(categories);

        ResponseEntity<?> response = controller.getCategories();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    private ProductDetailResponse buildDetail(Long id, String name) {
        return new ProductDetailResponse(id, "SKU-001", name, "desc",
                new BigDecimal("29.99"), null, "Brand", 100,
                new CategoryResponse(1L, "Electronics", null),
                List.of(), null, null);
    }
}
