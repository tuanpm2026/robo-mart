package com.robomart.inventory.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.inventory.entity.InventoryItem;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductId(Long productId);

    /** Loads the item with a row lock ({@code SELECT ... FOR UPDATE}) held until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.productId = :productId")
    Optional<InventoryItem> findByProductIdForUpdate(@Param("productId") Long productId);

    @Query("SELECT COUNT(i) FROM InventoryItem i WHERE i.availableQuantity < i.lowStockThreshold")
    long countLowStockItems();
}
