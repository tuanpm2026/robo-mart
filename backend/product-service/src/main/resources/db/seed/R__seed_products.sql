-- =============================================================================
-- Repeatable Seed Migration: Products, Categories & Product Images
-- Flyway re-runs this whenever the checksum changes.
-- Uses DELETE + INSERT for full idempotency.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Clean up (respect FK order: images -> products -> categories)
-- ---------------------------------------------------------------------------
DELETE FROM product_images;
DELETE FROM products;
DELETE FROM categories;

-- ---------------------------------------------------------------------------
-- 2. Categories
-- ---------------------------------------------------------------------------
INSERT INTO categories (id, name, description) VALUES
(1, 'Electronics',        'Smartphones, laptops, audio gear, and other electronic devices'),
(2, 'Home & Kitchen',     'Cookware, appliances, furniture, and home essentials'),
(3, 'Sports & Outdoors',  'Fitness equipment, outdoor gear, and athletic apparel'),
(4, 'Toys & Games',       'Board games, action figures, puzzles, and educational toys'),
(5, 'Books',              'Fiction, non-fiction, technical, and educational books');

-- ---------------------------------------------------------------------------
-- 3. Products
-- ---------------------------------------------------------------------------

-- ── Electronics (category_id = 1) ──────────────────────────────────────────
INSERT INTO products (id, sku, name, description, price, category_id, rating, brand, stock_quantity, version) VALUES
( 1, 'ELEC-001', 'ProMax Wireless Earbuds',          'Active noise cancelling true wireless earbuds with 30-hour battery life and IPX5 water resistance.',                    79.99,  1, 4.50, 'SoundCore',    250, 0),
( 2, 'ELEC-002', 'UltraSlim 15" Laptop',             '15.6-inch FHD display, AMD Ryzen 7, 16 GB RAM, 512 GB SSD. Perfect for work and entertainment.',                     899.00, 1, 4.70, 'TechNova',      45, 0),
( 3, 'ELEC-003', '4K Action Camera',                 'Waterproof 4K/60fps action camera with electronic image stabilization and dual LCD screens.',                         249.99, 1, 4.30, 'AdventureCam',  120, 0),
( 4, 'ELEC-004', 'Smart Home Hub 3rd Gen',            'Voice-controlled smart hub compatible with Zigbee, Z-Wave, Matter, and Wi-Fi devices.',                                129.99, 1, 4.10, 'HomeSphere',   180, 0),
( 5, 'ELEC-005', '27" Curved Gaming Monitor',         '27-inch QHD 165 Hz curved VA panel with 1 ms response time and AMD FreeSync Premium.',                                349.99, 1, 4.60, 'PixelEdge',     75, 0),
( 6, 'ELEC-006', 'Portable Bluetooth Speaker',        'Rugged IPX7 waterproof speaker with 360-degree sound and 20-hour playtime.',                                           59.99, 1, 4.20, 'SoundCore',    310, 0),
( 7, 'ELEC-007', 'Mechanical Gaming Keyboard',        'Hot-swappable mechanical keyboard with RGB backlighting and programmable macro keys.',                                  119.99, 1, 4.40, 'KeyMaster',    160, 0),
( 8, 'ELEC-008', 'Wireless Charging Pad',             'Qi-certified 15 W fast wireless charger compatible with all Qi-enabled devices.',                                       24.99, 1, 4.00, 'ChargePro',    500, 0),
( 9, 'ELEC-009', 'Noise Cancelling Headphones',       'Over-ear headphones with adaptive ANC, 40-hour battery, and multipoint Bluetooth 5.3.',                                199.99, 1, 4.80, 'AudioMax',      90, 0),
(10, 'ELEC-010', 'Smart Fitness Watch',                'GPS fitness watch with heart rate, SpO2, sleep tracking, and 14-day battery life.',                                    179.99, 1, 4.30, 'FitPulse',     200, 0),

-- ── Home & Kitchen (category_id = 2) ───────────────────────────────────────
(11, 'HOME-001', 'Stainless Steel French Press',      '34 oz double-wall insulated French press with 4-level filtration system.',                                              34.99, 2, 4.60, 'BrewCraft',    320, 0),
(12, 'HOME-002', 'Digital Air Fryer 6 Qt',            '6-quart digital air fryer with 8 preset cooking programs and dishwasher-safe basket.',                                  89.99, 2, 4.50, 'KitchenPro',   140, 0),
(13, 'HOME-003', 'Robot Vacuum Cleaner',              'LiDAR navigation robot vacuum with mopping, auto-empty station, and app control.',                                    399.99, 2, 4.40, 'CleanBot',      60, 0),
(14, 'HOME-004', 'Bamboo Cutting Board Set',          'Set of 3 organic bamboo cutting boards with juice grooves and easy-grip handles.',                                      29.99, 2, 4.70, 'EcoKitchen',   275, 0),
(15, 'HOME-005', 'Ceramic Non-Stick Cookware Set',    '12-piece ceramic-coated cookware set. PFOA-free, oven-safe to 450 F, and dishwasher safe.',                            149.99, 2, 4.30, 'GreenChef',     85, 0),
(16, 'HOME-006', 'Smart LED Desk Lamp',               'Adjustable LED desk lamp with wireless charging base, 5 color temperatures, and USB-C port.',                           49.99, 2, 4.20, 'LumiDesk',     190, 0),
(17, 'HOME-007', 'Electric Kettle 1.7L',              'Variable temperature electric kettle with keep-warm function and boil-dry protection.',                                  44.99, 2, 4.50, 'BrewCraft',    230, 0),
(18, 'HOME-008', 'Memory Foam Pillow',                'Cooling gel-infused memory foam pillow with adjustable loft and bamboo cover.',                                          39.99, 2, 4.10, 'DreamRest',    350, 0),
(19, 'HOME-009', 'Countertop Blender 1200W',          'High-performance blender with 64 oz BPA-free pitcher, variable speed, and pulse function.',                              79.99, 2, 4.40, 'BlendMax',     110, 0),
(20, 'HOME-010', 'Cast Iron Dutch Oven 6 Qt',         'Pre-seasoned 6-quart cast iron Dutch oven with self-basting lid. Oven safe to 500 F.',                                  64.99, 2, 4.80, 'IronForge',    150, 0),

-- ── Sports & Outdoors (category_id = 3) ────────────────────────────────────
(21, 'SPRT-001', 'Adjustable Dumbbell Set',           'Adjustable dumbbells from 5 to 52.5 lbs each with quick-change weight selector.',                                     299.99, 3, 4.70, 'IronFlex',      55, 0),
(22, 'SPRT-002', 'Yoga Mat Premium 6mm',              'Extra-thick 6 mm TPE yoga mat with alignment lines and carrying strap. Non-toxic and eco-friendly.',                     34.99, 3, 4.50, 'ZenFit',       280, 0),
(23, 'SPRT-003', 'Hydration Backpack 2L',             'Lightweight trail running hydration pack with 2 L bladder and reflective accents.',                                      54.99, 3, 4.30, 'TrailRunner',  165, 0),
(24, 'SPRT-004', 'Resistance Band Set',               '5-band set (10-50 lbs) with door anchor, ankle straps, and carrying bag.',                                               24.99, 3, 4.20, 'FlexFit',      400, 0),
(25, 'SPRT-005', 'Camping Tent 4-Person',             'Waterproof 4-person dome tent with rainfly, vestibule, and 5-minute setup.',                                            189.99, 3, 4.60, 'WildPeak',      70, 0),
(26, 'SPRT-006', 'Insulated Water Bottle 32oz',       'Triple-insulated stainless steel bottle. Keeps drinks cold 24 hrs or hot 12 hrs.',                                       29.99, 3, 4.40, 'HydroCore',    450, 0),
(27, 'SPRT-007', 'Running Shoes - Neutral',           'Lightweight neutral running shoes with responsive foam midsole and breathable mesh upper.',                             129.99, 3, 4.50, 'StrideMax',    200, 0),
(28, 'SPRT-008', 'Folding Bike 7-Speed',              'Compact folding commuter bike with 20-inch wheels, 7-speed Shimano gears, and disc brakes.',                           449.99, 3, 4.30, 'UrbanRide',     30, 0),
(29, 'SPRT-009', 'Foam Roller Set',                   '3-piece foam roller set with textured high-density roller, lacrosse ball, and massage stick.',                            27.99, 3, 4.10, 'RecoverPro',   220, 0),
(30, 'SPRT-010', 'Trekking Poles Pair',               'Carbon fiber trekking poles with cork grips, quick-lock adjustment, and tungsten tips.',                                  69.99, 3, 4.60, 'WildPeak',     130, 0),

-- ── Toys & Games (category_id = 4) ─────────────────────────────────────────
(31, 'TOYS-001', 'Building Blocks 1000-Piece Set',    'Creative building block set with 1000 pieces in 15 colors. Compatible with major brands.',                               29.99, 4, 4.80, 'BrickWorld',   300, 0),
(32, 'TOYS-002', 'Strategy Board Game',               'Award-winning strategy board game for 2-4 players, ages 10+. Average playtime 60-90 minutes.',                           44.99, 4, 4.70, 'GameForge',    180, 0),
(33, 'TOYS-003', 'Remote Control Racing Car',         '1:16 scale RC car with 25 mph top speed, 4WD, and rechargeable battery. Ages 8+.',                                       59.99, 4, 4.30, 'SpeedKing',    150, 0),
(34, 'TOYS-004', 'Science Experiment Kit',            '60+ experiments in chemistry, physics, and biology. Includes lab tools and illustrated guide. Ages 8-14.',                39.99, 4, 4.50, 'SciSpark',     200, 0),
(35, 'TOYS-005', '1000-Piece Jigsaw Puzzle',          'Premium 1000-piece jigsaw puzzle featuring a stunning mountain landscape. Poster included.',                              19.99, 4, 4.20, 'PuzzleMaster', 260, 0),
(36, 'TOYS-006', 'Wooden Chess Set',                  'Hand-carved walnut and maple chess set with felted pieces and folding storage board.',                                    49.99, 4, 4.60, 'ClassicPlay',  110, 0),
(37, 'TOYS-007', 'Drone with Camera',                 'Mini drone with 1080p camera, altitude hold, headless mode, and 3 batteries. Ages 14+.',                                 89.99, 4, 4.10, 'SkyRover',      95, 0),
(38, 'TOYS-008', 'Magnetic Tile Building Set',        '100-piece magnetic tile set with translucent colors. STEM toy for ages 3+.',                                             44.99, 4, 4.70, 'MagBuild',     240, 0),
(39, 'TOYS-009', 'Card Game Party Pack',              'Fast-paced party card game for 4-10 players. 300 cards with expansion pack. Ages 12+.',                                   24.99, 4, 4.40, 'GameForge',    350, 0),
(40, 'TOYS-010', 'Plush Dinosaur Collection',         'Set of 5 realistic plush dinosaurs (T-Rex, Triceratops, Stegosaurus, Brachiosaurus, Pteranodon). Ages 3+.',               34.99, 4, 4.50, 'CuddlePals',  190, 0),

-- ── Books (category_id = 5) ────────────────────────────────────────────────
(41, 'BOOK-001', 'Clean Code: A Handbook',            'Robert C. Martin''s classic guide to writing readable, maintainable, and agile software.',                                39.99, 5, 4.70, 'Prentice Hall', 180, 0),
(42, 'BOOK-002', 'The Pragmatic Programmer',          '20th anniversary edition. From journeyman to master -- timeless advice for modern developers.',                           49.99, 5, 4.80, 'Addison-Wesley', 150, 0),
(43, 'BOOK-003', 'Designing Data-Intensive Apps',     'Martin Kleppmann''s deep dive into the architecture of reliable, scalable, and maintainable data systems.',                45.99, 5, 4.90, 'O''Reilly',     130, 0),
(44, 'BOOK-004', 'The Great Gatsby',                  'F. Scott Fitzgerald''s masterpiece of the Jazz Age. A tale of wealth, love, and the American Dream.',                     12.99, 5, 4.40, 'Scribner',     400, 0),
(45, 'BOOK-005', 'Atomic Habits',                     'James Clear''s practical guide to building good habits and breaking bad ones through tiny changes.',                       16.99, 5, 4.80, 'Avery',        500, 0),
(46, 'BOOK-006', 'Sapiens: A Brief History',          'Yuval Noah Harari explores the history of humankind from the Stone Age to the Silicon Age.',                              18.99, 5, 4.60, 'Harper',       350, 0),
(47, 'BOOK-007', 'Introduction to Algorithms',        'Comprehensive textbook covering a broad range of algorithms in depth. 4th edition, MIT Press.',                           89.99, 5, 4.50, 'MIT Press',     80, 0),
(48, 'BOOK-008', 'Dune',                              'Frank Herbert''s epic science fiction saga of politics, religion, and ecology on the desert planet Arrakis.',              14.99, 5, 4.70, 'Ace Books',    320, 0),
(49, 'BOOK-009', 'The Art of War',                    'Sun Tzu''s ancient treatise on military strategy, with modern commentary and annotations.',                                 9.99, 5, 4.30, 'Shambhala',    600, 0),
(50, 'BOOK-010', 'System Design Interview Vol. 1',    'Step-by-step framework for system design interviews. Covers 16 real-world systems with diagrams.',                        34.99, 5, 4.60, 'ByteByByte',   220, 0);

-- ---------------------------------------------------------------------------
-- 4. Product Images (2-3 images per product)
-- ---------------------------------------------------------------------------
INSERT INTO product_images (id, product_id, image_url, alt_text, display_order) VALUES

-- ── Electronics Images ─────────────────────────────────────────────────────
(  1,  1, 'https://images.robomart.com/products/ELEC-001-1.jpg', 'ProMax Wireless Earbuds - front view',             1),
(  2,  1, 'https://images.robomart.com/products/ELEC-001-2.jpg', 'ProMax Wireless Earbuds - in charging case',       2),
(  3,  1, 'https://images.robomart.com/products/ELEC-001-3.jpg', 'ProMax Wireless Earbuds - worn in ear',            3),
(  4,  2, 'https://images.robomart.com/products/ELEC-002-1.jpg', 'UltraSlim 15" Laptop - open front view',          1),
(  5,  2, 'https://images.robomart.com/products/ELEC-002-2.jpg', 'UltraSlim 15" Laptop - side profile',             2),
(  6,  2, 'https://images.robomart.com/products/ELEC-002-3.jpg', 'UltraSlim 15" Laptop - keyboard close-up',        3),
(  7,  3, 'https://images.robomart.com/products/ELEC-003-1.jpg', '4K Action Camera - front with housing',           1),
(  8,  3, 'https://images.robomart.com/products/ELEC-003-2.jpg', '4K Action Camera - accessory bundle',             2),
(  9,  4, 'https://images.robomart.com/products/ELEC-004-1.jpg', 'Smart Home Hub 3rd Gen - front view',             1),
( 10,  4, 'https://images.robomart.com/products/ELEC-004-2.jpg', 'Smart Home Hub 3rd Gen - in living room',         2),
( 11,  5, 'https://images.robomart.com/products/ELEC-005-1.jpg', '27" Curved Gaming Monitor - front view',          1),
( 12,  5, 'https://images.robomart.com/products/ELEC-005-2.jpg', '27" Curved Gaming Monitor - side angle',          2),
( 13,  5, 'https://images.robomart.com/products/ELEC-005-3.jpg', '27" Curved Gaming Monitor - ports close-up',      3),
( 14,  6, 'https://images.robomart.com/products/ELEC-006-1.jpg', 'Portable Bluetooth Speaker - front view',         1),
( 15,  6, 'https://images.robomart.com/products/ELEC-006-2.jpg', 'Portable Bluetooth Speaker - outdoors',           2),
( 16,  7, 'https://images.robomart.com/products/ELEC-007-1.jpg', 'Mechanical Gaming Keyboard - top view',           1),
( 17,  7, 'https://images.robomart.com/products/ELEC-007-2.jpg', 'Mechanical Gaming Keyboard - RGB lighting',       2),
( 18,  7, 'https://images.robomart.com/products/ELEC-007-3.jpg', 'Mechanical Gaming Keyboard - keycap detail',      3),
( 19,  8, 'https://images.robomart.com/products/ELEC-008-1.jpg', 'Wireless Charging Pad - top view',                1),
( 20,  8, 'https://images.robomart.com/products/ELEC-008-2.jpg', 'Wireless Charging Pad - with phone',              2),
( 21,  9, 'https://images.robomart.com/products/ELEC-009-1.jpg', 'Noise Cancelling Headphones - front view',        1),
( 22,  9, 'https://images.robomart.com/products/ELEC-009-2.jpg', 'Noise Cancelling Headphones - folded',            2),
( 23,  9, 'https://images.robomart.com/products/ELEC-009-3.jpg', 'Noise Cancelling Headphones - ear cushion',       3),
( 24, 10, 'https://images.robomart.com/products/ELEC-010-1.jpg', 'Smart Fitness Watch - watch face',                1),
( 25, 10, 'https://images.robomart.com/products/ELEC-010-2.jpg', 'Smart Fitness Watch - on wrist',                  2),

-- ── Home & Kitchen Images ──────────────────────────────────────────────────
( 26, 11, 'https://images.robomart.com/products/HOME-001-1.jpg', 'Stainless Steel French Press - full view',        1),
( 27, 11, 'https://images.robomart.com/products/HOME-001-2.jpg', 'Stainless Steel French Press - pouring',          2),
( 28, 12, 'https://images.robomart.com/products/HOME-002-1.jpg', 'Digital Air Fryer 6 Qt - front view',             1),
( 29, 12, 'https://images.robomart.com/products/HOME-002-2.jpg', 'Digital Air Fryer 6 Qt - basket open',            2),
( 30, 12, 'https://images.robomart.com/products/HOME-002-3.jpg', 'Digital Air Fryer 6 Qt - control panel',          3),
( 31, 13, 'https://images.robomart.com/products/HOME-003-1.jpg', 'Robot Vacuum Cleaner - top view',                 1),
( 32, 13, 'https://images.robomart.com/products/HOME-003-2.jpg', 'Robot Vacuum Cleaner - with dock station',        2),
( 33, 13, 'https://images.robomart.com/products/HOME-003-3.jpg', 'Robot Vacuum Cleaner - side profile',             3),
( 34, 14, 'https://images.robomart.com/products/HOME-004-1.jpg', 'Bamboo Cutting Board Set - all three sizes',     1),
( 35, 14, 'https://images.robomart.com/products/HOME-004-2.jpg', 'Bamboo Cutting Board Set - in use',              2),
( 36, 15, 'https://images.robomart.com/products/HOME-005-1.jpg', 'Ceramic Non-Stick Cookware Set - full set',       1),
( 37, 15, 'https://images.robomart.com/products/HOME-005-2.jpg', 'Ceramic Non-Stick Cookware Set - frying pan',     2),
( 38, 15, 'https://images.robomart.com/products/HOME-005-3.jpg', 'Ceramic Non-Stick Cookware Set - interior coat',  3),
( 39, 16, 'https://images.robomart.com/products/HOME-006-1.jpg', 'Smart LED Desk Lamp - on desk',                   1),
( 40, 16, 'https://images.robomart.com/products/HOME-006-2.jpg', 'Smart LED Desk Lamp - charging phone',            2),
( 41, 17, 'https://images.robomart.com/products/HOME-007-1.jpg', 'Electric Kettle 1.7L - front view',               1),
( 42, 17, 'https://images.robomart.com/products/HOME-007-2.jpg', 'Electric Kettle 1.7L - temperature display',      2),
( 43, 18, 'https://images.robomart.com/products/HOME-008-1.jpg', 'Memory Foam Pillow - front view',                 1),
( 44, 18, 'https://images.robomart.com/products/HOME-008-2.jpg', 'Memory Foam Pillow - cross-section',              2),
( 45, 18, 'https://images.robomart.com/products/HOME-008-3.jpg', 'Memory Foam Pillow - on bed',                     3),
( 46, 19, 'https://images.robomart.com/products/HOME-009-1.jpg', 'Countertop Blender 1200W - assembled',            1),
( 47, 19, 'https://images.robomart.com/products/HOME-009-2.jpg', 'Countertop Blender 1200W - smoothie demo',        2),
( 48, 20, 'https://images.robomart.com/products/HOME-010-1.jpg', 'Cast Iron Dutch Oven 6 Qt - with lid',            1),
( 49, 20, 'https://images.robomart.com/products/HOME-010-2.jpg', 'Cast Iron Dutch Oven 6 Qt - interior view',       2),
( 50, 20, 'https://images.robomart.com/products/HOME-010-3.jpg', 'Cast Iron Dutch Oven 6 Qt - cooking stew',        3),

-- ── Sports & Outdoors Images ───────────────────────────────────────────────
( 51, 21, 'https://images.robomart.com/products/SPRT-001-1.jpg', 'Adjustable Dumbbell Set - both dumbbells',        1),
( 52, 21, 'https://images.robomart.com/products/SPRT-001-2.jpg', 'Adjustable Dumbbell Set - weight selector',       2),
( 53, 21, 'https://images.robomart.com/products/SPRT-001-3.jpg', 'Adjustable Dumbbell Set - in use',                3),
( 54, 22, 'https://images.robomart.com/products/SPRT-002-1.jpg', 'Yoga Mat Premium 6mm - rolled out',               1),
( 55, 22, 'https://images.robomart.com/products/SPRT-002-2.jpg', 'Yoga Mat Premium 6mm - alignment lines',          2),
( 56, 23, 'https://images.robomart.com/products/SPRT-003-1.jpg', 'Hydration Backpack 2L - front view',              1),
( 57, 23, 'https://images.robomart.com/products/SPRT-003-2.jpg', 'Hydration Backpack 2L - worn on trail',           2),
( 58, 24, 'https://images.robomart.com/products/SPRT-004-1.jpg', 'Resistance Band Set - all five bands',            1),
( 59, 24, 'https://images.robomart.com/products/SPRT-004-2.jpg', 'Resistance Band Set - with accessories',          2),
( 60, 24, 'https://images.robomart.com/products/SPRT-004-3.jpg', 'Resistance Band Set - exercise demo',             3),
( 61, 25, 'https://images.robomart.com/products/SPRT-005-1.jpg', 'Camping Tent 4-Person - fully pitched',           1),
( 62, 25, 'https://images.robomart.com/products/SPRT-005-2.jpg', 'Camping Tent 4-Person - interior space',          2),
( 63, 25, 'https://images.robomart.com/products/SPRT-005-3.jpg', 'Camping Tent 4-Person - packed in bag',           3),
( 64, 26, 'https://images.robomart.com/products/SPRT-006-1.jpg', 'Insulated Water Bottle 32oz - front view',        1),
( 65, 26, 'https://images.robomart.com/products/SPRT-006-2.jpg', 'Insulated Water Bottle 32oz - cap detail',        2),
( 66, 27, 'https://images.robomart.com/products/SPRT-007-1.jpg', 'Running Shoes - side profile',                    1),
( 67, 27, 'https://images.robomart.com/products/SPRT-007-2.jpg', 'Running Shoes - sole tread pattern',              2),
( 68, 27, 'https://images.robomart.com/products/SPRT-007-3.jpg', 'Running Shoes - on foot running',                 3),
( 69, 28, 'https://images.robomart.com/products/SPRT-008-1.jpg', 'Folding Bike 7-Speed - unfolded',                 1),
( 70, 28, 'https://images.robomart.com/products/SPRT-008-2.jpg', 'Folding Bike 7-Speed - folded compact',           2),
( 71, 29, 'https://images.robomart.com/products/SPRT-009-1.jpg', 'Foam Roller Set - all three pieces',              1),
( 72, 29, 'https://images.robomart.com/products/SPRT-009-2.jpg', 'Foam Roller Set - texture detail',                2),
( 73, 30, 'https://images.robomart.com/products/SPRT-010-1.jpg', 'Trekking Poles Pair - extended',                  1),
( 74, 30, 'https://images.robomart.com/products/SPRT-010-2.jpg', 'Trekking Poles Pair - cork grip close-up',        2),
( 75, 30, 'https://images.robomart.com/products/SPRT-010-3.jpg', 'Trekking Poles Pair - collapsed for storage',     3),

-- ── Toys & Games Images ────────────────────────────────────────────────────
( 76, 31, 'https://images.robomart.com/products/TOYS-001-1.jpg', 'Building Blocks 1000-Piece Set - box front',      1),
( 77, 31, 'https://images.robomart.com/products/TOYS-001-2.jpg', 'Building Blocks 1000-Piece Set - built models',   2),
( 78, 31, 'https://images.robomart.com/products/TOYS-001-3.jpg', 'Building Blocks 1000-Piece Set - color variety',  3),
( 79, 32, 'https://images.robomart.com/products/TOYS-002-1.jpg', 'Strategy Board Game - box and components',        1),
( 80, 32, 'https://images.robomart.com/products/TOYS-002-2.jpg', 'Strategy Board Game - gameplay setup',            2),
( 81, 33, 'https://images.robomart.com/products/TOYS-003-1.jpg', 'Remote Control Racing Car - side view',           1),
( 82, 33, 'https://images.robomart.com/products/TOYS-003-2.jpg', 'Remote Control Racing Car - with controller',     2),
( 83, 33, 'https://images.robomart.com/products/TOYS-003-3.jpg', 'Remote Control Racing Car - action shot',         3),
( 84, 34, 'https://images.robomart.com/products/TOYS-004-1.jpg', 'Science Experiment Kit - box contents',           1),
( 85, 34, 'https://images.robomart.com/products/TOYS-004-2.jpg', 'Science Experiment Kit - volcano experiment',     2),
( 86, 35, 'https://images.robomart.com/products/TOYS-005-1.jpg', '1000-Piece Jigsaw Puzzle - box art',              1),
( 87, 35, 'https://images.robomart.com/products/TOYS-005-2.jpg', '1000-Piece Jigsaw Puzzle - in progress',          2),
( 88, 36, 'https://images.robomart.com/products/TOYS-006-1.jpg', 'Wooden Chess Set - board with pieces',            1),
( 89, 36, 'https://images.robomart.com/products/TOYS-006-2.jpg', 'Wooden Chess Set - piece detail',                 2),
( 90, 36, 'https://images.robomart.com/products/TOYS-006-3.jpg', 'Wooden Chess Set - folded storage',               3),
( 91, 37, 'https://images.robomart.com/products/TOYS-007-1.jpg', 'Drone with Camera - in flight',                   1),
( 92, 37, 'https://images.robomart.com/products/TOYS-007-2.jpg', 'Drone with Camera - controller and batteries',    2),
( 93, 38, 'https://images.robomart.com/products/TOYS-008-1.jpg', 'Magnetic Tile Building Set - castle build',       1),
( 94, 38, 'https://images.robomart.com/products/TOYS-008-2.jpg', 'Magnetic Tile Building Set - tile detail',        2),
( 95, 38, 'https://images.robomart.com/products/TOYS-008-3.jpg', 'Magnetic Tile Building Set - child playing',      3),
( 96, 39, 'https://images.robomart.com/products/TOYS-009-1.jpg', 'Card Game Party Pack - box and cards',            1),
( 97, 39, 'https://images.robomart.com/products/TOYS-009-2.jpg', 'Card Game Party Pack - game in progress',         2),
( 98, 40, 'https://images.robomart.com/products/TOYS-010-1.jpg', 'Plush Dinosaur Collection - all five dinos',      1),
( 99, 40, 'https://images.robomart.com/products/TOYS-010-2.jpg', 'Plush Dinosaur Collection - T-Rex close-up',      2),
(100, 40, 'https://images.robomart.com/products/TOYS-010-3.jpg', 'Plush Dinosaur Collection - size comparison',     3),

-- ── Books Images ───────────────────────────────────────────────────────────
(101, 41, 'https://images.robomart.com/products/BOOK-001-1.jpg', 'Clean Code - front cover',                        1),
(102, 41, 'https://images.robomart.com/products/BOOK-001-2.jpg', 'Clean Code - back cover',                         2),
(103, 42, 'https://images.robomart.com/products/BOOK-002-1.jpg', 'The Pragmatic Programmer - front cover',          1),
(104, 42, 'https://images.robomart.com/products/BOOK-002-2.jpg', 'The Pragmatic Programmer - spine view',           2),
(105, 43, 'https://images.robomart.com/products/BOOK-003-1.jpg', 'Designing Data-Intensive Apps - front cover',     1),
(106, 43, 'https://images.robomart.com/products/BOOK-003-2.jpg', 'Designing Data-Intensive Apps - sample page',     2),
(107, 44, 'https://images.robomart.com/products/BOOK-004-1.jpg', 'The Great Gatsby - front cover',                  1),
(108, 44, 'https://images.robomart.com/products/BOOK-004-2.jpg', 'The Great Gatsby - back cover',                   2),
(109, 45, 'https://images.robomart.com/products/BOOK-005-1.jpg', 'Atomic Habits - front cover',                     1),
(110, 45, 'https://images.robomart.com/products/BOOK-005-2.jpg', 'Atomic Habits - chapter preview',                 2),
(111, 45, 'https://images.robomart.com/products/BOOK-005-3.jpg', 'Atomic Habits - back cover with quotes',          3),
(112, 46, 'https://images.robomart.com/products/BOOK-006-1.jpg', 'Sapiens: A Brief History - front cover',          1),
(113, 46, 'https://images.robomart.com/products/BOOK-006-2.jpg', 'Sapiens: A Brief History - back cover',           2),
(114, 47, 'https://images.robomart.com/products/BOOK-007-1.jpg', 'Introduction to Algorithms - front cover',        1),
(115, 47, 'https://images.robomart.com/products/BOOK-007-2.jpg', 'Introduction to Algorithms - table of contents',  2),
(116, 47, 'https://images.robomart.com/products/BOOK-007-3.jpg', 'Introduction to Algorithms - sample chapter',     3),
(117, 48, 'https://images.robomart.com/products/BOOK-008-1.jpg', 'Dune - front cover',                              1),
(118, 48, 'https://images.robomart.com/products/BOOK-008-2.jpg', 'Dune - back cover',                               2),
(119, 49, 'https://images.robomart.com/products/BOOK-009-1.jpg', 'The Art of War - front cover',                     1),
(120, 49, 'https://images.robomart.com/products/BOOK-009-2.jpg', 'The Art of War - annotated page',                  2),
(121, 50, 'https://images.robomart.com/products/BOOK-010-1.jpg', 'System Design Interview Vol. 1 - front cover',    1),
(122, 50, 'https://images.robomart.com/products/BOOK-010-2.jpg', 'System Design Interview Vol. 1 - diagram sample', 2),
(123, 50, 'https://images.robomart.com/products/BOOK-010-3.jpg', 'System Design Interview Vol. 1 - back cover',     3);

-- ---------------------------------------------------------------------------
-- 5. Reset sequences to follow the last inserted ID
-- ---------------------------------------------------------------------------
SELECT setval('categories_id_seq',     (SELECT MAX(id) FROM categories));
SELECT setval('products_id_seq',       (SELECT MAX(id) FROM products));
SELECT setval('product_images_id_seq', (SELECT MAX(id) FROM product_images));
