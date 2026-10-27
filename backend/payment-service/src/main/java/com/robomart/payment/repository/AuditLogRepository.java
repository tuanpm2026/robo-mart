package com.robomart.payment.repository;

import com.robomart.payment.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a WHERE " +
            "(:actor IS NULL OR a.actor = :actor) AND " +
            "(:action IS NULL OR a.action = :action) AND " +
            "(:entityType IS NULL OR a.entityType = :entityType) AND " +
            "(:entityId IS NULL OR a.entityId = :entityId) AND " +
            "(:traceId IS NULL OR a.traceId = :traceId) AND " +
            "(cast(:from as timestamp) IS NULL OR a.createdAt >= :from) AND " +
            "(cast(:to as timestamp) IS NULL OR a.createdAt <= :to) ORDER BY a.createdAt DESC, a.id DESC")
    Page<AuditLog> search(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("traceId") String traceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
