-- =============================================================================
-- Repeatable Seed Migration: Orders and Order Items
-- Flyway re-runs this whenever the checksum changes.
-- Uses DELETE + INSERT for full idempotency.
-- =============================================================================

DELETE FROM order_status_history;
DELETE FROM order_items;
DELETE FROM orders;

-- Orders in various statuses
INSERT INTO orders (id, user_id, total_amount, status, shipping_address, version) VALUES
(1,  'user-001', 164.97, 'PENDING',              '{"street":"123 Main St","city":"San Francisco","state":"CA","zip":"94105","country":"US"}', 0),
(2,  'user-001', 899.00, 'INVENTORY_RESERVING',   '{"street":"123 Main St","city":"San Francisco","state":"CA","zip":"94105","country":"US"}', 0),
(3,  'user-002', 334.98, 'PAYMENT_PROCESSING',    '{"street":"456 Oak Ave","city":"Los Angeles","state":"CA","zip":"90001","country":"US"}', 0),
(4,  'user-002', 79.99,  'CONFIRMED',             '{"street":"456 Oak Ave","city":"Los Angeles","state":"CA","zip":"90001","country":"US"}', 0),
(5,  'user-003', 449.99, 'SHIPPED',               '{"street":"789 Pine Rd","city":"Seattle","state":"WA","zip":"98101","country":"US"}', 0),
(6,  'user-003', 64.99,  'DELIVERED',             '{"street":"789 Pine Rd","city":"Seattle","state":"WA","zip":"98101","country":"US"}', 0),
(7,  'user-004', 349.99, 'CANCELLED',             '{"street":"321 Elm Blvd","city":"Portland","state":"OR","zip":"97201","country":"US"}', 0),
(8,  'user-004', 199.99, 'CONFIRMED',             '{"street":"321 Elm Blvd","city":"Portland","state":"OR","zip":"97201","country":"US"}', 0),
(9,  'user-005', 129.97, 'PENDING',               '{"street":"555 Birch Ln","city":"Austin","state":"TX","zip":"73301","country":"US"}', 0),
(10, 'user-005', 509.96, 'DELIVERED',             '{"street":"555 Birch Ln","city":"Austin","state":"TX","zip":"73301","country":"US"}', 0);

-- Order items (2-3 items per order, referencing product IDs from product_db)
INSERT INTO order_items (id, order_id, product_id, product_name, quantity, unit_price, subtotal) VALUES
(1,  1,  1,  'ProMax Wireless Earbuds',     1, 79.99,  79.99),
(2,  1,  6,  'Portable Bluetooth Speaker',  1, 59.99,  59.99),
(3,  1,  8,  'Wireless Charging Pad',       1, 24.99,  24.99),
(4,  2,  2,  'UltraSlim 15" Laptop',        1, 899.00, 899.00),
(5,  3,  22, 'Yoga Mat Premium 6mm',        1, 34.99,  34.99),
(6,  3,  21, 'Adjustable Dumbbell Set',     1, 299.99, 299.99),
(7,  4,  1,  'ProMax Wireless Earbuds',     1, 79.99,  79.99),
(8,  5,  28, 'Folding Bike 7-Speed',        1, 449.99, 449.99),
(9,  6,  20, 'Cast Iron Dutch Oven 6 Qt',   1, 64.99,  64.99),
(10, 7,  5,  '27" Curved Gaming Monitor',   1, 349.99, 349.99),
(11, 8,  9,  'Noise Cancelling Headphones', 1, 199.99, 199.99),
(12, 9,  26, 'Insulated Water Bottle 32oz', 2, 29.99,  59.98),
(13, 9,  30, 'Trekking Poles Pair',         1, 69.99,  69.99),
(14, 10, 3,  '4K Action Camera',            1, 249.99, 249.99),
(15, 10, 22, 'Yoga Mat Premium 6mm',        2, 34.99,  69.98),
(16, 10, 25, 'Camping Tent 4-Person',       1, 189.99, 189.99);

-- Status history entries
INSERT INTO order_status_history (order_id, status, changed_at) VALUES
(1,  'PENDING',              NOW() - INTERVAL '2 hours'),
(2,  'PENDING',              NOW() - INTERVAL '1 hour'),
(2,  'INVENTORY_RESERVING',  NOW() - INTERVAL '59 minutes'),
(3,  'PENDING',              NOW() - INTERVAL '45 minutes'),
(3,  'INVENTORY_RESERVING',  NOW() - INTERVAL '44 minutes'),
(3,  'PAYMENT_PROCESSING',   NOW() - INTERVAL '43 minutes'),
(4,  'PENDING',              NOW() - INTERVAL '1 day'),
(4,  'INVENTORY_RESERVING',  NOW() - INTERVAL '1 day' + INTERVAL '1 minute'),
(4,  'PAYMENT_PROCESSING',   NOW() - INTERVAL '1 day' + INTERVAL '2 minutes'),
(4,  'CONFIRMED',            NOW() - INTERVAL '1 day' + INTERVAL '3 minutes'),
(5,  'PENDING',              NOW() - INTERVAL '3 days'),
(5,  'CONFIRMED',            NOW() - INTERVAL '3 days' + INTERVAL '5 minutes'),
(5,  'SHIPPED',              NOW() - INTERVAL '2 days'),
(6,  'PENDING',              NOW() - INTERVAL '7 days'),
(6,  'CONFIRMED',            NOW() - INTERVAL '7 days' + INTERVAL '5 minutes'),
(6,  'SHIPPED',              NOW() - INTERVAL '5 days'),
(6,  'DELIVERED',            NOW() - INTERVAL '3 days'),
(7,  'PENDING',              NOW() - INTERVAL '2 days'),
(7,  'CANCELLED',            NOW() - INTERVAL '2 days' + INTERVAL '30 minutes'),
(8,  'PENDING',              NOW() - INTERVAL '5 days'),
(8,  'CONFIRMED',            NOW() - INTERVAL '5 days' + INTERVAL '5 minutes'),
(9,  'PENDING',              NOW() - INTERVAL '30 minutes'),
(10, 'PENDING',              NOW() - INTERVAL '10 days'),
(10, 'CONFIRMED',            NOW() - INTERVAL '10 days' + INTERVAL '5 minutes'),
(10, 'SHIPPED',              NOW() - INTERVAL '8 days'),
(10, 'DELIVERED',            NOW() - INTERVAL '6 days');

-- Reset sequences
SELECT setval('orders_id_seq', (SELECT MAX(id) FROM orders));
SELECT setval('order_items_id_seq', (SELECT MAX(id) FROM order_items));
