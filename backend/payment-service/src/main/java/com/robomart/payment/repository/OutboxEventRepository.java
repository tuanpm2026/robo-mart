package com.robomart.payment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.payment.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
