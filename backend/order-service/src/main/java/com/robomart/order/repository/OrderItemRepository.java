package com.robomart.order.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.order.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    @Query("SELECT i.order.id, COUNT(i) FROM OrderItem i WHERE i.order.id IN :orderIds GROUP BY i.order.id")
    List<Object[]> countsByOrderIds(@Param("orderIds") List<Long> orderIds);
}
