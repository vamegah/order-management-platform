-- ==============================================================================
-- Inventory Service — Initial Schema
-- ------------------------------------------------------------------------------
-- Flyway migration V1. Creates:
--   - inventory.stock_level
--   - inventory.reservation
--
-- The `inventory` schema and the `inventory_svc` role are created by the
-- platform's shared initialization (Phase 0, File 6). This migration only
-- creates the tables inside the schema.
--
-- IMMUTABILITY: once applied, this migration must not be edited. Schema
-- changes go in new migrations.
-- ==============================================================================

-- ==============================================================================
-- Table: stock_level
-- ==============================================================================
-- One row per (SKU, warehouse). Tracks on-hand and reserved quantities.
--
-- INVARIANTS (also enforced in the entity):
--   quantity_on_hand >= 0
--   quantity_reserved >= 0
--   quantity_reserved <= quantity_on_hand
-- ==============================================================================
CREATE TABLE inventory.stock_level (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sku                 varchar(64) NOT NULL,
    warehouse_id        varchar(50) NOT NULL,

    -- Total units physically in the warehouse.
    quantity_on_hand    integer NOT NULL DEFAULT 0,

    -- Units held for pending reservations.
    quantity_reserved   integer NOT NULL DEFAULT 0,

    -- Optimistic-locking version. JPA increments on every save; concurrent
    -- transactions that read the same version conflict at commit.
    version             bigint NOT NULL DEFAULT 0,

    last_updated        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_stock_level_sku_warehouse UNIQUE (sku, warehouse_id),

    CONSTRAINT chk_stock_level_quantities
        CHECK (quantity_on_hand >= 0
           AND quantity_reserved >= 0
           AND quantity_reserved <= quantity_on_hand)
);

COMMENT ON TABLE inventory.stock_level IS
    'Current stock per SKU per warehouse. Optimistic-locked.';
COMMENT ON COLUMN inventory.stock_level.version IS
    'JPA optimistic locking version.';

CREATE INDEX idx_stock_level_sku ON inventory.stock_level(sku);

-- ==============================================================================
-- Table: reservation
-- ==============================================================================
-- Holds part of a StockLevel for an order. Each reservation transitions
-- through ACTIVE → COMMITTED | RELEASED | EXPIRED.
-- ==============================================================================
CREATE TABLE inventory.reservation (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sku             varchar(64) NOT NULL,
    warehouse_id    varchar(50) NOT NULL,
    quantity        integer NOT NULL,
    order_id        uuid NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'ACTIVE',

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_reservation_status
        CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED'))
);

COMMENT ON TABLE inventory.reservation IS
    'Stock reservations. Lifecycle: ACTIVE → COMMITTED | RELEASED | EXPIRED.';

-- Query pattern: find reservations by order (e.g., when the order is cancelled).
CREATE INDEX idx_reservation_order_sku ON inventory.reservation(order_id, sku);

-- Query pattern: find expired ACTIVE reservations (background job, Phase 7).
CREATE INDEX idx_reservation_status_created
    ON inventory.reservation(status, created_at);