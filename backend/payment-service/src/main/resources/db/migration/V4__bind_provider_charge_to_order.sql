ALTER TABLE mock_gateway_operations ADD COLUMN request_order_id VARCHAR(100);

-- Recover order bindings from V3's length-prefixed identity before enforcing uniqueness.
UPDATE mock_gateway_operations
SET request_order_id = substring(request_identity FROM position(':' IN request_identity) + 1
                                FOR split_part(request_identity, ':', 1)::INTEGER)
WHERE operation_key LIKE 'charge:%'
  AND CASE WHEN request_identity ~ '^[0-9]{1,3}:'
           THEN split_part(request_identity, ':', 1)::INTEGER > 0 ELSE FALSE END;

-- Refund operations have no order binding. Historical duplicate charges require review;
-- never silently delete or merge financial records to make this constraint pass.
CREATE UNIQUE INDEX idx_mock_gateway_charge_order ON mock_gateway_operations(request_order_id)
    WHERE request_order_id IS NOT NULL;
