-- ==============================================================================
-- Order Service — Seed Data
-- ------------------------------------------------------------------------------
-- Flyway migration V2. Development and test data.
--
-- References seed products and customers by hardcoded UUIDs from previous
-- migrations. This is intentional: it demonstrates the snapshot pattern
-- (order lines copy product details rather than joining).
--
-- UUID CONVENTION
--   Orders:        00000000-0000-0000-0000-0000000003XX
--   Order lines:   00000000-0000-0000-0000-0000000004XX
--   Status hist:   00000000-0000-0000-0000-0000000005XX
-- ==============================================================================

-- ==============================================================================
-- Orders
-- ==============================================================================
INSERT INTO orders.purchase_order
    (id, order_number, customer_id, customer_email, status,
     subtotal_amount, tax_amount, shipping_amount, total_amount, currency,
     shipping_line1, shipping_line2, shipping_city, shipping_state,
     shipping_postal_code, shipping_country, placed_at, notes)
VALUES
    -- Order 1: Alice's first order, DELIVERED (fully historical)
    ('00000000-0000-0000-0000-000000000301',
     'ORD-2025-0001001',
     '00000000-0000-0000-0000-000000000101',   -- Alice (customer-service seed)
     'alice@omp.local',
     'DELIVERED',
     1299.0000, 103.9200, 0.0000, 1402.9200, 'USD',
     '200 Shipping Ave', NULL, 'Oakland', 'CA', '94607', 'US',
     '2025-01-08 14:23:11+00',
     'Leave at front door'),

    -- Order 2: Bob's order, CONFIRMED (active fulfillment)
    ('00000000-0000-0000-0000-000000000302',
     'ORD-2025-0001002',
     '00000000-0000-0000-0000-000000000102',   -- Bob
     'bob@omp.local',
     'CONFIRMED',
     999.0000, 79.9200, 9.9900, 1088.9100, 'USD',
     '300 Both Blvd', 'Suite 200', 'New York', 'NY', '10001', 'US',
     '2025-01-14 09:15:00+00',
     NULL),

    -- Order 3: Alice's cart-in-progress, DRAFT (not yet placed)
    ('00000000-0000-0000-0000-000000000303',
     'ORD-2025-0001003',
     '00000000-0000-0000-0000-000000000101',   -- Alice
     'alice@omp.local',
     'DRAFT',
     2598.0000, 0.0000, 0.0000, 2598.0000, 'USD',
     '200 Shipping Ave', NULL, 'Oakland', 'CA', '94607', 'US',
     NULL,
     NULL)
ON CONFLICT (id) DO NOTHING;

-- ==============================================================================
-- Order lines
-- ==============================================================================
INSERT INTO orders.order_line
    (id, order_id, product_id, product_sku, product_name,
     unit_price_amount, unit_price_currency, quantity, line_total)
VALUES
    -- Order 1: 1x Air Laptop 13"
    ('00000000-0000-0000-0000-000000000401',
     '00000000-0000-0000-0000-000000000301',
     '00000000-0000-0000-0000-000000000202',   -- Air Laptop 13" (product-service seed)
     'LAPTOP-AIR-13',
     'Air Laptop 13"',
     1299.0000, 'USD', 1, 1299.0000),

    -- Order 2: 1x Flagship Phone 6
    ('00000000-0000-0000-0000-000000000402',
     '00000000-0000-0000-0000-000000000302',
     '00000000-0000-0000-0000-000000000203',   -- Flagship Phone 6
     'PHONE-FLAG-6',
     'Flagship Phone 6',
     999.0000, 'USD', 1, 999.0000),

    -- Order 3: 1x Pro Laptop 14" (DRAFT)
    ('00000000-0000-0000-0000-000000000403',
     '00000000-0000-0000-0000-000000000303',
     '00000000-0000-0000-0000-000000000201',   -- Pro Laptop 14"
     'LAPTOP-PRO-14',
     'Pro Laptop 14"',
     2499.0000, 'USD', 1, 2499.0000),

    -- Order 3: 1x Air Laptop 13" (DRAFT, second line)
    ('00000000-0000-0000-0000-000000000404',
     '00000000-0000-0000-0000-000000000303',
     '00000000-0000-0000-0000-000000000202',   -- Air Laptop 13"
     'LAPTOP-AIR-13',
     'Air Laptop 13"',
     99.0000, 'USD', 1, 99.0000)
ON CONFLICT (id) DO NOTHING;

-- ==============================================================================
-- Status history
-- ==============================================================================
INSERT INTO orders.order_status_history
    (id, order_id, from_status, to_status, reason, changed_by, changed_at)
VALUES
    -- Order 1: DRAFT → PLACED → CONFIRMED → SHIPPED → DELIVERED
    ('00000000-0000-0000-0000-000000000501',
     '00000000-0000-0000-0000-000000000301',
     NULL, 'DRAFT', 'Order created', 'alice', '2025-01-08 14:20:00+00'),

    ('00000000-0000-0000-0000-000000000502',
     '00000000-0000-0000-0000-000000000301',
     'DRAFT', 'PLACED', 'Checkout', 'alice', '2025-01-08 14:23:11+00'),

    ('00000000-0000-0000-0000-000000000503',
     '00000000-0000-0000-0000-000000000301',
     'PLACED', 'CONFIRMED', 'Payment succeeded', 'system', '2025-01-08 14:23:45+00'),

    ('00000000-0000-0000-0000-000000000504',
     '00000000-0000-0000-0000-000000000301',
     'CONFIRMED', 'SHIPPED', 'Shipped via UPS 1Z...', 'admin', '2025-01-09 10:00:00+00'),

    ('00000000-0000-0000-0000-000000000505',
     '00000000-0000-0000-0000-000000000301',
     'SHIPPED', 'DELIVERED', 'Signed for by A. Customer', 'system', '2025-01-10 15:42:00+00'),

    -- Order 2: DRAFT → PLACED → CONFIRMED
    ('00000000-0000-0000-0000-000000000506',
     '00000000-0000-0000-0000-000000000302',
     NULL, 'DRAFT', 'Order created', 'bob', '2025-01-14 09:10:00+00'),

    ('00000000-0000-0000-0000-000000000507',
     '00000000-0000-0000-0000-000000000302',
     'DRAFT', 'PLACED', 'Checkout', 'bob', '2025-01-14 09:15:00+00'),

    ('00000000-0000-0000-0000-000000000508',
     '00000000-0000-0000-0000-000000000302',
     'PLACED', 'CONFIRMED', 'Payment succeeded', 'system', '2025-01-14 09:15:30+00'),

    -- Order 3: still DRAFT
    ('00000000-0000-0000-0000-000000000509',
     '00000000-0000-0000-0000-000000000303',
     NULL, 'DRAFT', 'Cart created', 'alice', '2025-01-15 08:00:00+00')
ON CONFLICT (id) DO NOTHING;