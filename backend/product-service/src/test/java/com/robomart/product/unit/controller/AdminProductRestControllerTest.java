package com.robomart.product.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.robomart.product.controller.AdminProductRestController;
import com.robomart.product.dto.AuditLogDto;
import com.robomart.product.dto.CategoryResponse;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ImageOrderItem;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductImageResponse;
import com.robomart.product.dto.ReorderImagesRequest;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.service.AuditLogService;
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
    private AuditLogService auditLogService;

    @Mock
    private Tracer tracer;

    private AdminProductRestController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminProductRestController(productService, productImageService, auditLogService, tracer);
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

    @Test
    void shouldUploadImagesAndReturn201() {
        MockMultipartFile file = new MockMultipartFile("files", "img.png", "image/png", new byte[]{1});
        ProductImageResponse img = new ProductImageResponse(1L, "http://cdn/img.png", null, 0);
        when(productImageService.uploadImages(eq(1L), any())).thenReturn(List.of(img));

        ResponseEntity<?> response = controller.uploadImages(1L, List.of(file));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void shouldDeleteImageAndReturn204() {
        doNothing().when(productImageService).deleteImage(1L, 10L);

        ResponseEntity<?> response = controller.deleteImage(1L, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(productImageService).deleteImage(1L, 10L);
    }

    @Test
    void shouldReorderImagesAndReturn200() {
        ReorderImagesRequest request = new ReorderImagesRequest(
                List.of(new ImageOrderItem(10L, 0), new ImageOrderItem(20L, 1)));
        ProductImageResponse img1 = new ProductImageResponse(10L, "http://cdn/a.png", null, 0);
        ProductImageResponse img2 = new ProductImageResponse(20L, "http://cdn/b.png", null, 1);
        when(productImageService.reorderImages(eq(1L), any())).thenReturn(List.of(img1, img2));

        ResponseEntity<?> response = controller.reorderImages(1L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldSearchAuditLogsAndReturnPagedResponse() {
        AuditLogDto dto = new AuditLogDto(1L, "admin", "CREATE", "Product", "42",
                null, null, Instant.now());
        when(auditLogService.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1));

        ResponseEntity<?> response = controller.searchAuditLogs(
                null, null, null, null, null, null, null, 0, 20);

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
