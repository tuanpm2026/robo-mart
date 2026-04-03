package com.robomart.order.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.order.entity.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    List<OrderStatusHistory> findByOrderIdOrderByChangedAtDesc(Long orderId);

    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAsc(Long orderId);
}
