package com.robomart.order.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.order.entity.SagaAuditLog;

public interface SagaAuditLogRepository extends JpaRepository<SagaAuditLog, Long> {

    List<SagaAuditLog> findBySagaIdOrderByExecutedAtAsc(String sagaId);

    List<SagaAuditLog> findByOrderIdOrderByExecutedAtAsc(String orderId);
}
