package com.robomart.inventory.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.robomart.inventory.entity.InventoryItem;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductId(Long productId);

    @Query("SELECT COUNT(i) FROM InventoryItem i WHERE i.availableQuantity < i.lowStockThreshold")
    long countLowStockItems();
}
