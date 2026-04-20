package com.robomart.order.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.order.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    int countByOrderId(Long orderId);

    @Query("SELECT i.order.id, COUNT(i) FROM OrderItem i WHERE i.order.id IN :orderIds GROUP BY i.order.id")
    List<Object[]> countsByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Query("SELECT i.productId, i.productName, SUM(i.quantity), SUM(i.subtotal) FROM OrderItem i " +
           "JOIN i.order o WHERE o.createdAt BETWEEN :from AND :to AND o.status != 'CANCELLED' " +
           "GROUP BY i.productId, i.productName ORDER BY SUM(i.quantity) DESC")
    List<Object[]> findTopSellingProducts(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query("SELECT i.productName, SUM(i.subtotal) FROM OrderItem i " +
           "JOIN i.order o WHERE o.createdAt BETWEEN :from AND :to AND o.status != 'CANCELLED' " +
           "GROUP BY i.productId, i.productName ORDER BY SUM(i.subtotal) DESC")
    List<Object[]> findRevenueByProduct(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
