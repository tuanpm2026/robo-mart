package com.robomart.notification.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.notification.entity.FailedEvent;

public interface FailedEventRepository extends JpaRepository<FailedEvent, Long> {

    Page<FailedEvent> findByStatusNot(String status, Pageable pageable);

    List<FailedEvent> findByStatus(String status, Pageable pageable);

    boolean existsByOriginalTopicAndAggregateIdAndStatus(String originalTopic, String aggregateId, String status);
}
