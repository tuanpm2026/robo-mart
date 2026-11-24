CREATE INDEX idx_orders_embedded_recovery_due ON orders(updated_at,id)
    WHERE orchestrator_owner='EMBEDDED' AND status IN (
        'PENDING','INVENTORY_RESERVING','PAYMENT_PROCESSING',
        'PAYMENT_REFUNDING','INVENTORY_RELEASING','REFUND_FAILED');
