package com.robomart.product.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

@Service
@Transactional
public class ProductImageService {

    private static final int MAX_IMAGES_PER_PRODUCT = 10;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ImageStorageService imageStorageService;
    private final ProductMapper productMapper;

    public ProductImageService(
            ProductRepository productRepository,
            ProductImageRepository productImageRepository,
            ImageStorageService imageStorageService,
            ProductMapper productMapper) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.imageStorageService = imageStorageService;
        this.productMapper = productMapper;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<ProductImageResponse> uploadImages(Long productId, List<MultipartFile> files) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        long currentCount = productImageRepository.countByProductId(productId);
        if (currentCount + files.size() > MAX_IMAGES_PER_PRODUCT) {
            throw new ValidationException(
                    "Cannot upload " + files.size() + " images: product already has "
                    + currentCount + " images. Max 10 per product.");
        }

        int nextOrder = (int) currentCount;
        List<String> storedUrls = new ArrayList<>();
        List<ProductImage> saved = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                String imageUrl = imageStorageService.store(productId, file);
                storedUrls.add(imageUrl);
                ProductImage image = new ProductImage();
                image.setProduct(product);
                image.setImageUrl(imageUrl);
                image.setDisplayOrder(nextOrder++);
                saved.add(productImageRepository.save(image));
            }
        } catch (Exception e) {
            // Clean up any files already written to disk before the failure
            storedUrls.forEach(imageStorageService::delete);
            throw e;
        }
        return productMapper.toImageResponseList(saved);
    }

    public void deleteImage(Long productId, Long imageId) {
        // Single fetch — eliminates existsByIdAndProductId + findById double round-trip
        ProductImage image = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Image " + imageId + " not found for product " + productId));
        imageStorageService.delete(image.getImageUrl());
        productImageRepository.deleteById(imageId);
    }

    public List<ProductImageResponse> reorderImages(Long productId, ReorderImagesRequest request) {
        // Require all images to be included — partial reorder causes duplicate displayOrder values
        long totalImages = productImageRepository.countByProductId(productId);
        if (request.items().size() != totalImages) {
            throw new ValidationException(
                    "Reorder request must include all " + totalImages + " images for this product");
        }

        // Single loop: validate ownership and update in one pass (eliminates N+1 and TOCTOU gap)
        for (ImageOrderItem item : request.items()) {
            ProductImage image = productImageRepository.findByIdAndProductId(item.imageId(), productId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Image " + item.imageId() + " not found for product " + productId));
            image.setDisplayOrder(item.displayOrder());
            productImageRepository.save(image);
        }

        return productMapper.toImageResponseList(
                productImageRepository.findByProductIdOrderByDisplayOrderAsc(productId));
    }
}
