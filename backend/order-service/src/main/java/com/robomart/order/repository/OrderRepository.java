package com.robomart.order.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserId(String userId, Pageable pageable);

    @EntityGraph(attributePaths = {"items"})
    List<Order> findByStatusIn(List<OrderStatus> statuses);
}
