package com.robomart.product.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    @org.springframework.cache.annotation.Caching(evict = {
        @CacheEvict(value = "productDetail", key = "#productId"),
        @CacheEvict(value = "productSearch", allEntries = true)
    })
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
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) storedUrls.forEach(imageStorageService::delete);
                }
            });
        }
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
            // Clean up any objects already stored before the failure
            if (!synchronizedTransaction) storedUrls.forEach(imageStorageService::delete);
            throw e;
        }
        return productMapper.toImageResponseList(saved);
    }

    @org.springframework.cache.annotation.Caching(evict = {
        @CacheEvict(value = "productDetail", key = "#productId"),
        @CacheEvict(value = "productSearch", allEntries = true)
    })
    public void deleteImage(Long productId, Long imageId) {
        // Single fetch — eliminates existsByIdAndProductId + findById double round-trip
        ProductImage image = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Image " + imageId + " not found for product " + productId));
        productImageRepository.deleteById(imageId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    imageStorageService.delete(image.getImageUrl());
                }
            });
        } else {
            imageStorageService.delete(image.getImageUrl());
        }
    }

    @org.springframework.cache.annotation.Caching(evict = {
        @CacheEvict(value = "productDetail", key = "#productId"),
        @CacheEvict(value = "productSearch", allEntries = true)
    })
    public List<ProductImageResponse> reorderImages(Long productId, ReorderImagesRequest request) {
        List<ProductImage> images = new ArrayList<>(productImageRepository.findByProductIdOrderByDisplayOrderAsc(productId));
        var byId = images.stream().collect(java.util.stream.Collectors.toMap(ProductImage::getId, image -> image));
        var ids = new java.util.HashSet<Long>();
        var positions = new java.util.HashSet<Integer>();
        if (request.items().size() != images.size()) {
            throw new ValidationException("Reorder request must include all " + images.size() + " images for this product");
        }
        for (ImageOrderItem item : request.items()) {
            if (!byId.containsKey(item.imageId()) || !ids.add(item.imageId())
                    || item.displayOrder() == null || item.displayOrder() < 0
                    || item.displayOrder() >= images.size() || !positions.add(item.displayOrder())) {
                throw new ValidationException("Image IDs and positions must be a complete unique permutation");
            }
        }
        request.items().forEach(item -> byId.get(item.imageId()).setDisplayOrder(item.displayOrder()));
        images.sort(java.util.Comparator.comparing(ProductImage::getDisplayOrder));
        return productMapper.toImageResponseList(images);
    }
}
