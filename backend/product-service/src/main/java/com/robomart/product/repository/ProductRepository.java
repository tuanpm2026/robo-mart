package com.robomart.product.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.product.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.category.id = :categoryId AND p.active = true")
    Page<Product> findByCategoryIdAndActive(@Param("categoryId") Long categoryId, Pageable pageable);

    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.active = true")
    Page<Product> findAllWithDetails(Pageable pageable);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.active = true")
    Optional<Product> findByIdWithDetails(@Param("id") Long id);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithDetailsIncludeInactive(@Param("id") Long id);

    boolean existsBySku(String sku);

    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.active = true " +
           "AND (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId)")
    Page<Product> searchByKeywordLike(
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            Pageable pageable);
}
