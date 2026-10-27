package com.robomart.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.payment.entity.RefundIntent;

public interface RefundIntentRepository extends JpaRepository<RefundIntent, Long> {
    Optional<RefundIntent> findByPaymentIdAndIdempotencyKey(Long paymentId, String key);
    boolean existsByPaymentIdAndStatusAndIdempotencyKeyNot(Long paymentId, String status, String key);
}
