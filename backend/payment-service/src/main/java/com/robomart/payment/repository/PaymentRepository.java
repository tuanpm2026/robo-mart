package com.robomart.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
    int lockPaymentRequest(@Param("lockKey") String lockKey);

    Optional<Payment> findByOrderId(String orderId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
