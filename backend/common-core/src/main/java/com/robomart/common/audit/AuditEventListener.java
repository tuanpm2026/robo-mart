package com.robomart.common.audit;

public interface AuditEventListener {
    void onAuditEvent(AuditEvent event);
}
