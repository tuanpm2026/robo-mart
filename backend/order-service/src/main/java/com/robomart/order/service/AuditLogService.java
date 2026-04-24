package com.robomart.order.service;

import com.robomart.common.audit.AuditEvent;
import com.robomart.common.audit.AuditEventListener;
import com.robomart.order.dto.AuditLogDto;
import com.robomart.order.entity.AuditLog;
import com.robomart.order.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuditLogService implements AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAuditEvent(AuditEvent event) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setActor(event.actor());
            auditLog.setAction(event.action().name());
            auditLog.setEntityType(event.entityType());
            auditLog.setEntityId(event.entityId());
            auditLog.setTraceId(event.traceId());
            auditLog.setCorrelationId(event.correlationId());
            auditLog.setCreatedAt(event.timestamp() != null ? event.timestamp() : Instant.now());
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.warn("Failed to persist audit log: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLogDto> search(String actor, String action, String entityType, String entityId,
                                     String traceId, Instant from, Instant to, Pageable pageable) {
        return auditLogRepository.search(actor, action, entityType, entityId, traceId, from, to, pageable)
                .map(a -> new AuditLogDto(a.getId(), a.getActor(), a.getAction(), a.getEntityType(),
                        a.getEntityId(), a.getTraceId(), a.getCorrelationId(), a.getCreatedAt()));
    }
}
