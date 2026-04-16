package com.robomart.order.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserId(String userId, Pageable pageable);

    @EntityGraph(attributePaths = {"items"})
    List<Order> findByStatusIn(List<OrderStatus> statuses);

    Page<Order> findByStatusIn(List<OrderStatus> statuses, Pageable pageable);

    long countByCreatedAtAfter(Instant since);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0.0) FROM Order o WHERE o.createdAt > :since")
    BigDecimal sumTotalAmountByCreatedAtAfter(@Param("since") Instant since);

    @Query(value = "SELECT DATE(o.created_at) as date, o.status, COUNT(*) as cnt " +
                   "FROM orders o WHERE o.created_at BETWEEN :from AND :to " +
                   "GROUP BY DATE(o.created_at), o.status ORDER BY date",
           nativeQuery = true)
    List<Object[]> findOrderTrends(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT o FROM Order o WHERE o.status IN :statuses AND o.updatedAt < :cutoff")
    List<Order> findStuckSagas(
            @Param("statuses") List<OrderStatus> statuses,
            @Param("cutoff") Instant cutoff
    );
}
