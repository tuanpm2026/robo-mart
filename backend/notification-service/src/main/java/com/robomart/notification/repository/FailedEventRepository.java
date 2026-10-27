package com.robomart.notification.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.dto.FailedEventSummary;

public interface FailedEventRepository extends JpaRepository<FailedEvent, Long> {
    Page<FailedEventSummary> findSummariesByStatusNot(String status, Pageable pageable);

    @Query("SELECT e.id FROM FailedEvent e WHERE e.rawRecordAvailable=true AND (e.status='PENDING' "
            + "OR (e.status='REPLAYING' AND e.replayClaimUntil<=:now)) ORDER BY e.firstFailedAt")
    List<Long> findReplayableIds(@Param("now") Instant now, Pageable pageable);
}
