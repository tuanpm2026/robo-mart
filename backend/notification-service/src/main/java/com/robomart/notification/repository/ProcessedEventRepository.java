package com.robomart.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.notification.entity.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByConsumerGroupAndEventId(String consumerGroup, String eventId);
}
