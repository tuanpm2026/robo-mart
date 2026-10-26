package com.robomart.order.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Override
    @EntityGraph(attributePaths = {"items"})
    Optional<Order> findById(Long id);

    @Query(value = "SELECT * FROM orders WHERE status = 'PAYMENT_PENDING' "
            + "AND NOT EXISTS (SELECT 1 FROM saga_recovery_state s WHERE s.order_id=orders.id "
            + "AND s.phase='PAYMENT' AND (s.manual_review_at IS NOT NULL OR s.completed_at IS NOT NULL)) "
            + "AND (next_payment_retry_at IS NULL OR next_payment_retry_at <= :now) "
            + "ORDER BY next_payment_retry_at NULLS FIRST, id LIMIT 1 FOR NO KEY UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Order> findNextPendingPaymentForUpdate(@Param("now") Instant now);

    @Query(value = "SELECT * FROM orders WHERE id = :id AND status = 'PAYMENT_PROCESSING' "
            + "AND updated_at <= :cutoff FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Order> findUncertainPaymentForUpdate(@Param("id") Long id, @Param("cutoff") Instant cutoff);

    @EntityGraph(attributePaths = {"items"})
    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    @Query("SELECT o.status FROM Order o WHERE o.id = :id")
    Optional<OrderStatus> findStatusById(@Param("id") Long id);

    /**
     * Compare-and-set status transition: moves the order to {@code to} only while it is still in
     * {@code from}, persisting the saga-owned columns carried by {@code order} in the same statement.
     * Returns {@code false} when another actor (cancellation, recovery) moved the order first; the
     * caller must then stop driving it. Must run inside a transaction.
     */
    default boolean transitionStatus(Order order, OrderStatus from, OrderStatus to) {
        return updateStatusIfCurrent(order.getId(), from, to, order.getReservationId(), order.getPaymentId(),
                order.getCancellationReason(), order.getNextPaymentRetryAt(), Instant.now()) == 1;
    }

    // clearAutomatically: under open-in-view the saga's Order may still be managed; detaching it stops a
    // later flush from overwriting this conditional write with stale in-memory state.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = :to, o.reservationId = :reservationId, o.paymentId = :paymentId, "
            + "o.cancellationReason = :cancellationReason, o.nextPaymentRetryAt = :nextPaymentRetryAt, "
            + "o.version = o.version + 1, o.updatedAt = :now WHERE o.id = :id AND o.status = :from")
    int updateStatusIfCurrent(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
            @Param("reservationId") String reservationId, @Param("paymentId") String paymentId,
            @Param("cancellationReason") String cancellationReason,
            @Param("nextPaymentRetryAt") Instant nextPaymentRetryAt, @Param("now") Instant now);

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

    // Items are needed by inventory release; the scheduled job has no session to load them lazily.
    @EntityGraph(attributePaths = {"items"})
    @Query("SELECT o FROM Order o WHERE o.status IN :statuses AND o.updatedAt < :cutoff")
    List<Order> findStuckSagas(
            @Param("statuses") List<OrderStatus> statuses,
            @Param("cutoff") Instant cutoff
    );
}
