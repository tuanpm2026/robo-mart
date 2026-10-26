package com.robomart.inventory.repository;

import java.util.Optional;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.inventory.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByOrderId(String orderId);

    /**
     * Inserts a reservation row unless one already exists for the order. On a concurrent
     * uncommitted insert for the same order PostgreSQL waits for that transaction and then skips,
     * so the {@code order_id} unique key serializes reserve vs. reserve and reserve vs. release.
     *
     * @return 1 if inserted, 0 if a row for the order already exists
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO reservations (reservation_id, order_id, status, created_at, updated_at) "
            + "VALUES (:reservationId, :orderId, :status, NOW(), NOW()) "
            + "ON CONFLICT (order_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("reservationId") String reservationId,
                       @Param("orderId") String orderId,
                       @Param("status") String status);

    /**
     * Atomically claims the release of an order's reservation (RESERVED to RELEASED). Exactly one
     * of any number of concurrent releases gets 1 and restores stock; the others get 0.
     *
     * @return 1 if this call claimed the release, 0 otherwise
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reservations SET status = 'RELEASED', updated_at = NOW() "
            + "WHERE order_id = :orderId AND status = 'RESERVED'", nativeQuery = true)
    int claimRelease(@Param("orderId") String orderId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reservations SET expires_at=:expires WHERE order_id=:orderId", nativeQuery = true)
    void setExpiry(@Param("orderId") String orderId, @Param("expires") Instant expires);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reservations SET status='COMMITTED', updated_at=now() "
            + "WHERE order_id=:orderId AND status='RESERVED'", nativeQuery = true)
    int claimCommit(@Param("orderId") String orderId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reservations SET status='RELEASED', updated_at=now() "
            + "WHERE order_id=:orderId AND status='COMMITTED'", nativeQuery = true)
    int claimCommittedReturn(@Param("orderId") String orderId);

}
