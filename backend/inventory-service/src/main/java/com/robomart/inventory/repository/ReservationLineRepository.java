package com.robomart.inventory.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.inventory.entity.ReservationLine;

public interface ReservationLineRepository extends JpaRepository<ReservationLine, Long> {

    /** Lines in product-id order, so multi-row stock updates always lock rows in the same order. */
    List<ReservationLine> findByOrderIdOrderByProductIdAsc(String orderId);
}
