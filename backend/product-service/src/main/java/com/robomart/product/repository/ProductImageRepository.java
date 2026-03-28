package com.robomart.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.product.entity.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdInOrderByDisplayOrderAsc(List<Long> productIds);
}
