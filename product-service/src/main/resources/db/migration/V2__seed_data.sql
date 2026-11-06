-- ==============================================================================
-- Product Service — Seed Data
-- ------------------------------------------------------------------------------
-- Flyway migration V2. Populates the schema with a small, realistic dataset
-- for development and testing.
--
-- This migration runs only in environments where it's included in the
-- classpath (i.e., always — but see note below about prod).
--
-- PROD NOTE
--   This seed data is harmless (a handful of rows) but pointless in prod.
--   The prod profile disables Flyway by setting an empty locations list
--   (see application-prod.yml, to be added in a later batch). For now,
--   Phase 1's prod profile will exclude V2 via Flyway's callback mechanism
--   or via a separate locations directory. Phase 1 uses the simple
--   "include everywhere" approach — the data is trivially removable.
--
-- UUID CONVENTION
--   IDs are hardcoded (rather than gen_random_uuid()) so tests can
--   reference specific rows. The `0` prefix identifies this as seed data.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- Categories
-- ------------------------------------------------------------------------------
INSERT INTO products.category (id, parent_id, name, slug, description, sort_order) VALUES
    ('00000000-0000-0000-0000-000000000101',
     NULL,
     'Electronics',
     'electronics',
     'Phones, laptops, and accessories',
     10),

    ('00000000-0000-0000-0000-000000000102',
     '00000000-0000-0000-0000-000000000101',
     'Laptops',
     'laptops',
     'Portable computers',
     10),

    ('00000000-0000-0000-0000-000000000103',
     '00000000-0000-0000-0000-000000000101',
     'Phones',
     'phones',
     'Smartphones and feature phones',
     20)
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------------------------
-- Products
-- ------------------------------------------------------------------------------
INSERT INTO products.product
    (id, sku, name, description, category_id, price_amount, price_currency, status)
VALUES
    ('00000000-0000-0000-0000-000000000201',
     'LAPTOP-PRO-14',
     'Pro Laptop 14"',
     'High-performance 14-inch laptop with 32GB RAM and 1TB SSD.',
     '00000000-0000-0000-0000-000000000102',
     2499.99, 'USD', 'ACTIVE'),

    ('00000000-0000-0000-0000-000000000202',
     'LAPTOP-AIR-13',
     'Air Laptop 13"',
     'Ultra-light 13-inch laptop with 16GB RAM and 512GB SSD.',
     '00000000-0000-0000-0000-000000000102',
     1299.00, 'USD', 'ACTIVE'),

    ('00000000-0000-0000-0000-000000000203',
     'PHONE-FLAG-6',
     'Flagship Phone 6',
     'Latest flagship smartphone with 6.7" display and 5G.',
     '00000000-0000-0000-0000-000000000103',
     999.00, 'USD', 'ACTIVE'),

    ('00000000-0000-0000-0000-000000000204',
     'PHONE-BUDGET-3',
     'Budget Phone 3',
     'Entry-level smartphone. Great battery, modest camera.',
     '00000000-0000-0000-0000-000000000103',
     199.99, 'USD', 'ACTIVE'),

    ('00000000-0000-0000-0000-000000000205',
     'LAPTOP-OLD-12',
     'Legacy Laptop 12"',
     'Previous-generation model. No longer sold.',
     '00000000-0000-0000-0000-000000000102',
     899.00, 'USD', 'DISCONTINUED')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------------------------
-- Product images
-- ------------------------------------------------------------------------------
INSERT INTO products.product_image (id, product_id, url, alt_text, sort_order) VALUES
    ('00000000-0000-0000-0000-000000000301',
     '00000000-0000-0000-0000-000000000201',
     'https://cdn.example.com/products/pro-laptop-14-front.jpg',
     'Pro Laptop 14" — front view',
     10),

    ('00000000-0000-0000-0000-000000000302',
     '00000000-0000-0000-0000-000000000201',
     'https://cdn.example.com/products/pro-laptop-14-side.jpg',
     'Pro Laptop 14" — side view',
     20),

    ('00000000-0000-0000-0000-000000000303',
     '00000000-0000-0000-0000-000000000202',
     'https://cdn.example.com/products/air-laptop-13-front.jpg',
     'Air Laptop 13" — front view',
     10),

    ('00000000-0000-0000-0000-000000000304',
     '00000000-0000-0000-0000-000000000203',
     'https://cdn.example.com/products/flagship-phone-6-front.jpg',
     'Flagship Phone 6 — front view',
     10)
ON CONFLICT (id) DO NOTHING;