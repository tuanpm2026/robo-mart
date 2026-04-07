package com.robomart.product.unit.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.common.exception.ValidationException;
import com.robomart.product.dto.ImageOrderItem;
import com.robomart.product.dto.ProductImageResponse;
import com.robomart.product.dto.ReorderImagesRequest;
import com.robomart.product.entity.Product;
import com.robomart.product.entity.ProductImage;
import com.robomart.product.exception.ProductNotFoundException;
import com.robomart.product.mapper.ProductMapper;
import com.robomart.product.repository.ProductImageRepository;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.ImageStorageService;
import com.robomart.product.service.ProductImageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductImageRepository productImageRepository;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ProductMapper productMapper;

    @InjectMocks
    private ProductImageService productImageService;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        testProduct = new Product();
        testProduct.setName("Test Product");
    }

    // ─── uploadImages ──────────────────────────────────────────────────────────

    @Test
    void uploadImages_underLimit_savesAndReturnsResponses() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "img.jpg", "image/jpeg", "data".getBytes());

        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productImageRepository.countByProductId(1L)).thenReturn(0L);
        when(imageStorageService.store(1L, file)).thenReturn("http://localhost:8081/images/1/img.jpg");
        ProductImage savedImage = new ProductImage();
        savedImage.setImageUrl("http://localhost:8081/images/1/img.jpg");
        savedImage.setDisplayOrder(0);
        when(productImageRepository.save(any(ProductImage.class))).thenReturn(savedImage);
        ProductImageResponse expectedResponse = new ProductImageResponse(1L, "http://localhost:8081/images/1/img.jpg", null, 0);
        when(productMapper.toImageResponseList(any())).thenReturn(List.of(expectedResponse));

        List<ProductImageResponse> result = productImageService.uploadImages(1L, List.of(file));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).imageUrl()).contains("img.jpg");
        verify(productImageRepository).save(any(ProductImage.class));
    }

    @Test
    void uploadImages_exceedsLimit_throwsValidationException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "img.jpg", "image/jpeg", "data".getBytes());

        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productImageRepository.countByProductId(1L)).thenReturn(9L); // 9 + 2 > 10

        assertThatThrownBy(() -> productImageService.uploadImages(1L, List.of(file, file)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Max 10 per product");

        verify(productImageRepository, never()).save(any());
    }

    @Test
    void uploadImages_productNotFound_throwsProductNotFoundException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "img.jpg", "image/jpeg", "data".getBytes());

        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productImageService.uploadImages(999L, List.of(file)))
                .isInstanceOf(ProductNotFoundException.class);

        verify(productImageRepository, never()).save(any());
    }

    @Test
    void uploadImages_storageFailure_cleansUpAlreadyStoredFiles() {
        MockMultipartFile file1 = new MockMultipartFile("file", "img1.jpg", "image/jpeg", "data1".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("file", "img2.jpg", "image/jpeg", "data2".getBytes());

        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productImageRepository.countByProductId(1L)).thenReturn(0L);
        when(imageStorageService.store(1L, file1)).thenReturn("http://localhost:8081/images/1/img1.jpg");
        when(imageStorageService.store(1L, file2)).thenThrow(new RuntimeException("Disk full"));

        assertThatThrownBy(() -> productImageService.uploadImages(1L, List.of(file1, file2)))
                .isInstanceOf(RuntimeException.class);

        // First file's URL should be cleaned up
        verify(imageStorageService).delete("http://localhost:8081/images/1/img1.jpg");
    }

    // ─── deleteImage ───────────────────────────────────────────────────────────

    @Test
    void deleteImage_validOwnership_deletesAndRemovesFile() {
        ProductImage image = new ProductImage();
        image.setImageUrl("http://localhost:8081/images/1/abc.jpg");

        when(productImageRepository.findByIdAndProductId(10L, 1L)).thenReturn(Optional.of(image));

        productImageService.deleteImage(1L, 10L);

        verify(imageStorageService).delete("http://localhost:8081/images/1/abc.jpg");
        verify(productImageRepository).deleteById(10L);
    }

    @Test
    void deleteImage_wrongProductId_throwsException() {
        when(productImageRepository.findByIdAndProductId(10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productImageService.deleteImage(2L, 10L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(imageStorageService, never()).delete(any());
        verify(productImageRepository, never()).deleteById(any());
    }

    // ─── reorderImages ─────────────────────────────────────────────────────────

    @Test
    void reorderImages_validItems_updatesDisplayOrder() {
        ReorderImagesRequest request = new ReorderImagesRequest(List.of(
                new ImageOrderItem(1L, 0),
                new ImageOrderItem(2L, 1)
        ));

        ProductImage img1 = new ProductImage();
        img1.setDisplayOrder(1);
        ProductImage img2 = new ProductImage();
        img2.setDisplayOrder(0);

        when(productImageRepository.countByProductId(10L)).thenReturn(2L);
        when(productImageRepository.findByIdAndProductId(1L, 10L)).thenReturn(Optional.of(img1));
        when(productImageRepository.findByIdAndProductId(2L, 10L)).thenReturn(Optional.of(img2));
        when(productImageRepository.findByProductIdOrderByDisplayOrderAsc(10L))
                .thenReturn(List.of(img1, img2));
        when(productMapper.toImageResponseList(any())).thenReturn(List.of(
                new ProductImageResponse(1L, "url1", null, 0),
                new ProductImageResponse(2L, "url2", null, 1)
        ));

        List<ProductImageResponse> result = productImageService.reorderImages(10L, request);

        assertThat(result).hasSize(2);
        assertThat(img1.getDisplayOrder()).isEqualTo(0);
        assertThat(img2.getDisplayOrder()).isEqualTo(1);
    }

    @Test
    void reorderImages_partialItems_throwsValidationException() {
        // Only 1 item in request but product has 2 images
        ReorderImagesRequest request = new ReorderImagesRequest(List.of(
                new ImageOrderItem(1L, 0)
        ));

        when(productImageRepository.countByProductId(10L)).thenReturn(2L);

        assertThatThrownBy(() -> productImageService.reorderImages(10L, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must include all 2 images");

        verify(productImageRepository, never()).save(any());
    }
}
