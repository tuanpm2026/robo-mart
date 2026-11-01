-- Publish existing inventory without rewriting catalog data or replaying stock mutations.
-- Later mutations carry a higher row version and supersede these snapshots safely.
INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
SELECT 'InventoryItem', product_id::text, 'stock_updated',
       jsonb_build_object('productId', product_id, 'availableQuantity', available_quantity,
                          'inventoryVersion', version)
FROM inventory_items
ORDER BY product_id;
