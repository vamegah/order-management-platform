-- ==============================================================================
-- Inventory Service — Seed Data
-- ------------------------------------------------------------------------------
-- Flyway migration V2. Dev/test seed data.
--
-- The SKUs match the products seeded in product-service (Phase 1, File 2).
-- This is intentional: end-to-end tests (Batch 7.4) create orders for these
-- products and expect inventory to exist.
--
-- UUID CONVENTION
--   Stock levels:  00000000-0000-0000-0000-0000000006XX
--   Reservations:  00000000-0000-0000-0000-0000000007XX
-- ==============================================================================

-- ==============================================================================
-- Stock levels
-- ==============================================================================
INSERT INTO inventory.stock_level
    (id, sku, warehouse_id, quantity_on_hand, quantity_reserved, version)
VALUES
    -- Warehouse WH-1 (primary)
    ('00000000-0000-0000-0000-000000000601', 'LAPTOP-PRO-14',  'WH-1', 100, 0, 0),
    ('00000000-0000-0000-0000-000000000602', 'LAPTOP-AIR-13',  'WH-1', 200, 0, 0),
    ('00000000-0000-0000-0000-000000000603', 'PHONE-FLAG-6',   'WH-1',  50, 0, 0),
    ('00000000-0000-0000-0000-000000000604', 'PHONE-BUDGET-3', 'WH-1', 500, 0, 0),
    ('00000000-0000-0000-0000-000000000605', 'LAPTOP-OLD-12',  'WH-1',   0, 0, 0),

    -- Warehouse WH-2 (secondary)
    ('00000000-0000-0000-0000-000000000611', 'LAPTOP-PRO-14',  'WH-2',  30, 0, 0),
    ('00000000-0000-0000-0000-000000000612', 'PHONE-FLAG-6',   'WH-2',  20, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- No seed reservations. Reservations are created at runtime by order-service.