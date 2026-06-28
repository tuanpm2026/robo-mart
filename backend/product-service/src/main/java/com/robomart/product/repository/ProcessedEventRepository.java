package com.robomart.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.product.entity.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByConsumerGroupAndEventId(String consumerGroup, String eventId);
}
