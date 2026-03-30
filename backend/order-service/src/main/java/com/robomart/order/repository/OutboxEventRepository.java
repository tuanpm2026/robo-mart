package com.robomart.order.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.order.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
