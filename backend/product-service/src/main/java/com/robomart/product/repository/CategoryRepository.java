package com.robomart.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.product.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
