-- V4__seed_demo_data.sql
-- Deterministic demo data for development and integration testing.
-- All IDs are explicit so tests can reference them reliably.
-- Password for all demo users: "password123"
-- BCrypt hash generated at cost 10.


-- ============================================================
-- 1. BUSINESSES
-- ============================================================

INSERT INTO business (business_id, name, email_domain) VALUES
    (1, 'Aussie Floors Group',   'aussiefloors.com.au'),
    (2, 'Premier Flooring Co',   'premierflooring.com.au');


-- ============================================================
-- 2. STORES
-- ============================================================
-- Business 1 → 2 stores.  Business 2 → 1 store.

INSERT INTO store (store_id, business_id, name, store_code, phone, email, street, suburb, state_code, postcode) VALUES
    (1, 1, 'Aussie Floors Sydney CBD',   'SYD-CBD',  '0290001111', 'sydcbd@aussiefloors.com.au',       '100 George Street',  'Sydney',      'NSW', '2000'),
    (2, 1, 'Aussie Floors Parramatta',   'SYD-PARR', '0290002222', 'parramatta@aussiefloors.com.au',   '45 Church Street',   'Parramatta',  'NSW', '2150'),
    (3, 2, 'Premier Flooring Melbourne',  'MEL-CBD',  '0390001111', 'melbourne@premierflooring.com.au', '200 Collins Street', 'Melbourne',   'VIC', '3000');


-- ============================================================
-- 3. USERS
-- ============================================================
-- Business 1: 4 users (2 per store).  Business 2: 5 users (all store 3).
-- BCrypt hash below = "password123" at cost 10.

INSERT INTO app_user (user_id, business_id, first_name, last_name, salesperson_code, email, password_hash) VALUES
    -- Business 1 / Store 1
    (1, 1, 'Liam',    'Carter',   'LC01', 'liam.carter@aussiefloors.com.au',     '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    (2, 1, 'Sophie',  'Nguyen',   'SN01', 'sophie.nguyen@aussiefloors.com.au',   '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    -- Business 1 / Store 2
    (3, 1, 'Jack',    'Williams', 'JW01', 'jack.williams@aussiefloors.com.au',   '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    (4, 1, 'Emma',    'Patel',    'EP01', 'emma.patel@aussiefloors.com.au',      '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    -- Business 2 / Store 3
    (5, 2, 'Oliver',  'Smith',    'OS01', 'oliver.smith@premierflooring.com.au', '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    (6, 2, 'Mia',     'Johnson',  'MJ01', 'mia.johnson@premierflooring.com.au', '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    (7, 2, 'Noah',    'Brown',    'NB01', 'noah.brown@premierflooring.com.au',  '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    (8, 2, 'Chloe',   'Taylor',   'CT01', 'chloe.taylor@premierflooring.com.au','$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi'),
    (9, 2, 'Ethan',   'Lee',      'EL01', 'ethan.lee@premierflooring.com.au',   '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi');


-- ============================================================
-- 4. USER–STORE ACCESS
-- ============================================================

INSERT INTO user_store_access (user_store_access_id, business_id, user_id, store_id) VALUES
    (1, 1, 1, 1),
    (2, 1, 2, 1),
    (3, 1, 3, 2),
    (4, 1, 4, 2),
    (5, 2, 5, 3),
    (6, 2, 6, 3),
    (7, 2, 7, 3),
    (8, 2, 8, 3),
    (9, 2, 9, 3);


-- ============================================================
-- 5. PRODUCTS  (4 per store — 2 SOFT, 2 HARD)
-- ============================================================
-- SOFT flooring is priced and stocked per LM (linear metre).
-- HARD flooring is priced and stocked per SQM (square metre).
-- Conversion: 1 LM = 3.66 SQM (standard 3.66 m wide roll).

INSERT INTO store_product (product_id, store_id, flooring_type, code, name, pricing_unit, price, cost, stock_quantity, stock_unit) VALUES
    -- Store 1 (Sydney CBD)
    ( 1, 1, 'SOFT', 'PLU-001', 'Plush Carpet Premium',     'LM',  45.00, 22.00, 320.00, 'LM'),
    ( 2, 1, 'SOFT', 'LOP-001', 'Loop Pile Carpet',          'LM',  38.00, 18.00, 250.00, 'LM'),
    ( 3, 1, 'HARD', 'SGT-001', 'Spotted Gum Timber',        'SQM', 89.00, 42.00, 180.00, 'SQM'),
    ( 4, 1, 'HARD', 'HVP-001', 'Hybrid Vinyl Plank',        'SQM', 62.00, 28.00, 400.00, 'SQM'),
    -- Store 2 (Parramatta)
    ( 5, 2, 'SOFT', 'WOL-001', 'Wool Twist Carpet',         'LM',  55.00, 28.00, 200.00, 'LM'),
    ( 6, 2, 'SOFT', 'NYL-001', 'Nylon Berber Carpet',       'LM',  42.00, 20.00, 300.00, 'LM'),
    ( 7, 2, 'HARD', 'OAK-001', 'Engineered Oak Flooring',   'SQM', 95.00, 45.00, 150.00, 'SQM'),
    ( 8, 2, 'HARD', 'LAM-001', 'Laminate Click Flooring',   'SQM', 48.00, 22.00, 350.00, 'SQM'),
    -- Store 3 (Melbourne)
    ( 9, 3, 'SOFT', 'FRZ-001', 'Frieze Carpet Deluxe',      'LM',  50.00, 25.00, 280.00, 'LM'),
    (10, 3, 'SOFT', 'SAX-001', 'Saxony Plush Carpet',       'LM',  60.00, 30.00, 220.00, 'LM'),
    (11, 3, 'HARD', 'BBO-001', 'Bamboo Flooring',           'SQM', 75.00, 35.00, 190.00, 'SQM'),
    (12, 3, 'HARD', 'PRC-001', 'Porcelain Tile Look Vinyl', 'SQM', 85.00, 40.00, 160.00, 'SQM');


-- ============================================================
-- 6. CHARGES  (4 per store — 2 SOFT, 2 HARD)
-- ============================================================

INSERT INTO store_charge (charge_id, store_id, flooring_type, code, name, price, cost) VALUES
    -- Store 1
    ( 1, 1, 'SOFT', 'INST-S',  'Carpet Installation',        15.00,  8.00),
    ( 2, 1, 'SOFT', 'UNDR-S',  'Carpet Underlay Supply',      8.00,  4.00),
    ( 3, 1, 'HARD', 'INST-H',  'Hard Floor Installation',    22.00, 12.00),
    ( 4, 1, 'HARD', 'UNDR-H',  'Hard Floor Underlay Supply', 10.00,  5.00),
    -- Store 2
    ( 5, 2, 'SOFT', 'INST-S',  'Carpet Installation',        16.00,  9.00),
    ( 6, 2, 'SOFT', 'FURN-S',  'Furniture Moving',          120.00, 60.00),
    ( 7, 2, 'HARD', 'INST-H',  'Hard Floor Installation',    24.00, 13.00),
    ( 8, 2, 'HARD', 'FURN-H',  'Furniture Moving',          120.00, 60.00),
    -- Store 3
    ( 9, 3, 'SOFT', 'INST-S',  'Carpet Installation',        14.00,  7.00),
    (10, 3, 'SOFT', 'DISP-S',  'Old Carpet Disposal',         5.00,  2.50),
    (11, 3, 'HARD', 'INST-H',  'Hard Floor Installation',    20.00, 11.00),
    (12, 3, 'HARD', 'DISP-H',  'Old Floor Disposal',          8.00,  4.00);


-- ============================================================
-- 7. SALES ORDERS  (2 per user = 18 total)
-- ============================================================
-- Order 1 is the fully populated order (customer, addresses, lines, note, photo, payment, invoice).
-- All other orders are header-only for dashboard testing.
--
-- Business 1: order_sequence_number 1–8
-- Business 2: order_sequence_number 1–10
--
-- Order 1 totals (LM pricing):
--   product line_total  = 8 LM × $45.00/LM = $360.00
--   charge  line_total  = 32    × $15.00    = $480.00
--   sale_price_ex_gst   = 360 + 480         = $840.00
--   total_cost          = 176 + 256         = $432.00
--   gp                  = 840 − 432         = $408.00
--   gp_percent          = 408 ÷ 840 × 100  =   48.57%

INSERT INTO sales_order (order_id, business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, order_status, supply_only, plan_numbers, proposed_lay_date, lay_date_status, sale_price_ex_gst, total_cost, gp, gp_percent, week_number, week_year, details_of_sale) VALUES
    -- User 1 (Liam, Store 1, Business 1)
    ( 1, 1, 1, 1, 1, 'ORD-1-0001', 'SOFT', 'ACCEPTED',          FALSE, NULL,      '2026-05-01', 'CONFIRMED',       840.00, 432.00, 408.00, 48.57, 15, 2026, 'Supply and install plush carpet to lounge and dining rooms. Furniture to be moved by installer.'),
    ( 2, 1, 1, 1, 2, 'ORD-1-0002', 'HARD', 'LEAD',              FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  15, 2026, NULL),
    -- User 2 (Sophie, Store 1, Business 1)
    ( 3, 1, 1, 2, 3, 'ORD-1-0003', 'SOFT', 'FOLLOW_UP',         FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  14, 2026, NULL),
    ( 4, 1, 1, 2, 4, 'ORD-1-0004', 'HARD', 'NEW_ACHIEVED_SALE', FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  14, 2026, NULL),
    -- User 3 (Jack, Store 2, Business 1)
    ( 5, 1, 2, 3, 5, 'ORD-1-0005', 'SOFT', 'LEAD',              FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  13, 2026, NULL),
    ( 6, 1, 2, 3, 6, 'ORD-1-0006', 'HARD', 'LAID',              FALSE, 'PLN-4420', '2026-03-20', 'CONFIRMED',       NULL,    NULL,   NULL,   NULL,  12, 2026, NULL),
    -- User 4 (Emma, Store 2, Business 1)
    ( 7, 1, 2, 4, 7, 'ORD-1-0007', 'SOFT', 'CANCELLED',         FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  11, 2026, NULL),
    ( 8, 1, 2, 4, 8, 'ORD-1-0008', 'HARD', 'LEAD',              FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  15, 2026, NULL),
    -- User 5 (Oliver, Store 3, Business 2)
    ( 9, 2, 3, 5, 1, 'ORD-2-0001', 'SOFT', 'LEAD',              FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  15, 2026, NULL),
    (10, 2, 3, 5, 2, 'ORD-2-0002', 'HARD', 'NEW_ACHIEVED_SALE', FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  14, 2026, NULL),
    -- User 6 (Mia, Store 3, Business 2)
    (11, 2, 3, 6, 3, 'ORD-2-0003', 'SOFT', 'FOLLOW_UP',         FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  13, 2026, NULL),
    (12, 2, 3, 6, 4, 'ORD-2-0004', 'HARD', 'ACCEPTED',          FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  15, 2026, NULL),
    -- User 7 (Noah, Store 3, Business 2)
    (13, 2, 3, 7, 5, 'ORD-2-0005', 'SOFT', 'LAID',              FALSE, 'PLN-8801', '2026-03-15', 'CONFIRMED',       NULL,    NULL,   NULL,   NULL,  12, 2026, NULL),
    (14, 2, 3, 7, 6, 'ORD-2-0006', 'HARD', 'LEAD',              FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  15, 2026, NULL),
    -- User 8 (Chloe, Store 3, Business 2)
    (15, 2, 3, 8, 7, 'ORD-2-0007', 'SOFT', 'LEAD',              FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  14, 2026, NULL),
    (16, 2, 3, 8, 8, 'ORD-2-0008', 'HARD', 'CANCELLED',         FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  11, 2026, NULL),
    -- User 9 (Ethan, Store 3, Business 2)
    (17, 2, 3, 9, 9, 'ORD-2-0009', 'SOFT', 'FOLLOW_UP',         FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  15, 2026, NULL),
    (18, 2, 3, 9,10, 'ORD-2-0010', 'HARD', 'ACCEPTED',          FALSE, NULL,       NULL,         NULL,              NULL,    NULL,   NULL,   NULL,  13, 2026, NULL);


-- ============================================================
-- 8. FULL CHILD DATA FOR ORDER 1  (Liam's carpet job)
-- ============================================================

-- 8a. Customer
INSERT INTO order_customer (order_customer_id, order_id, first_name, middle_name, last_name, email, mobile, home_phone, work_phone, company_name) VALUES
    (1, 1, 'James', NULL, 'Wilson', 'james.wilson@email.com', '0412345678', '0298765432', NULL, NULL);

-- 8b. Addresses  (install + billing)
INSERT INTO order_address (order_address_id, order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) VALUES
    (1, 1, 'INSTALLATION', NULL, '42',  'Oxford Street',  'Paddington', 'NSW', '2021'),
    (2, 1, 'BILLING',      '3',  '15', 'Pitt Street',    'Sydney',     'NSW', '2000');

-- 8c. Product line  (8 LM of Plush Carpet Premium)
-- SOFT flooring: salesperson enters quantity in LM (the pricing unit).
-- quantity_lm  = 8.00                       (entered by salesperson)
-- quantity_sqm = 8.00 × 3.66 = 29.28       (auto-derived: 1 LM = 3.66 SQM)
-- unit_price   = $45.00 per LM
-- line_total   = 8 × 45.00   = $360.00
-- line_cost    = 8 × 22.00   = $176.00
INSERT INTO order_product_line (order_product_line_id, order_id, product_id, product_code_snapshot, product_name_snapshot, pricing_unit_snapshot, price_snapshot, cost_snapshot, quantity_lm, quantity_sqm, unit_price, line_total, line_cost) VALUES
    (1, 1, 1, 'PLU-001', 'Plush Carpet Premium', 'LM', 45.00, 22.00, 8.00, 29.28, 45.00, 360.00, 176.00);

-- 8d. Charge line  (carpet installation — 32 units at $15)
-- Charges have no pricing_unit column; quantity is entered by the salesperson.
-- line_total = 32 × 15.00 = $480.00
-- line_cost  = 32 ×  8.00 = $256.00
INSERT INTO order_charge_line (order_charge_line_id, order_id, charge_id, charge_code_snapshot, charge_name_snapshot, price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) VALUES
    (1, 1, 1, 'INST-S', 'Carpet Installation', 15.00, 8.00, 32.00, 15.00, 480.00, 256.00);

-- 8e. Note
INSERT INTO order_note (order_note_id, order_id, note_text) VALUES
    (1, 1, 'Customer prefers Saturday morning installation. Access via rear lane behind Oxford Street.');

-- 8f. Stored files  (1 = site photo, 2 = invoice PDF)
INSERT INTO stored_file (stored_file_id, file_name, storage_path, mime_type, file_size) VALUES
    (1, 'site-photo-lounge.jpg',  '/uploads/1/orders/1/site-photo-lounge.jpg',  'image/jpeg',       2048576),
    (2, 'INV-1-0001-v1.pdf',     '/uploads/1/orders/1/INV-1-0001-v1.pdf',     'application/pdf',   102400);

-- 8g. Attachment  (links site photo to order)
INSERT INTO order_attachment (order_attachment_id, order_id, stored_file_id, attachment_kind) VALUES
    (1, 1, 1, 'PHOTO');

-- 8h. Payment  ($500 EFTPOS deposit)
INSERT INTO payment_transaction (payment_transaction_id, order_id, payment_method, amount, payment_reference, gateway_transaction_id, response_status, response_message) VALUES
    (1, 1, 'EFTPOS', 500.00, 'EFTPOS-20260414', NULL, NULL, NULL);

-- 8i. Invoice  (version 1)
-- sale_price_ex_gst  = 360 + 480         = $840.00
-- sale_price_inc_gst = 840 × 1.10        = $924.00  (10% Australian GST)
-- total_paid         = $500.00
-- balance_due        = 924 − 500         = $424.00
INSERT INTO invoice (invoice_id, order_id, version_number, invoice_date, due_date, details_of_sale_snapshot, sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due, stored_file_id, created_by_user_id) VALUES
    (1, 1, 1, '2026-04-14', '2026-04-28', 'Supply and install plush carpet to lounge and dining rooms. Furniture to be moved by installer.', 840.00, 924.00, 500.00, 424.00, 2, 1);


-- ============================================================
-- 9. RESET IDENTITY SEQUENCES
-- ============================================================
-- Ensures the next auto-generated ID starts after the highest seeded value.

SELECT setval(pg_get_serial_sequence('business',            'business_id'),            (SELECT MAX(business_id)            FROM business));
SELECT setval(pg_get_serial_sequence('store',               'store_id'),               (SELECT MAX(store_id)               FROM store));
SELECT setval(pg_get_serial_sequence('app_user',            'user_id'),                (SELECT MAX(user_id)                FROM app_user));
SELECT setval(pg_get_serial_sequence('user_store_access',   'user_store_access_id'),   (SELECT MAX(user_store_access_id)   FROM user_store_access));
SELECT setval(pg_get_serial_sequence('store_product',       'product_id'),             (SELECT MAX(product_id)             FROM store_product));
SELECT setval(pg_get_serial_sequence('store_charge',        'charge_id'),              (SELECT MAX(charge_id)              FROM store_charge));
SELECT setval(pg_get_serial_sequence('sales_order',         'order_id'),               (SELECT MAX(order_id)               FROM sales_order));
SELECT setval(pg_get_serial_sequence('order_customer',      'order_customer_id'),      (SELECT MAX(order_customer_id)      FROM order_customer));
SELECT setval(pg_get_serial_sequence('order_address',       'order_address_id'),       (SELECT MAX(order_address_id)       FROM order_address));
SELECT setval(pg_get_serial_sequence('order_product_line',  'order_product_line_id'),  (SELECT MAX(order_product_line_id)  FROM order_product_line));
SELECT setval(pg_get_serial_sequence('order_charge_line',   'order_charge_line_id'),   (SELECT MAX(order_charge_line_id)   FROM order_charge_line));
SELECT setval(pg_get_serial_sequence('order_note',          'order_note_id'),          (SELECT MAX(order_note_id)          FROM order_note));
SELECT setval(pg_get_serial_sequence('stored_file',         'stored_file_id'),         (SELECT MAX(stored_file_id)         FROM stored_file));
SELECT setval(pg_get_serial_sequence('order_attachment',    'order_attachment_id'),     (SELECT MAX(order_attachment_id)    FROM order_attachment));
SELECT setval(pg_get_serial_sequence('payment_transaction', 'payment_transaction_id'), (SELECT MAX(payment_transaction_id) FROM payment_transaction));
SELECT setval(pg_get_serial_sequence('invoice',             'invoice_id'),             (SELECT MAX(invoice_id)             FROM invoice));