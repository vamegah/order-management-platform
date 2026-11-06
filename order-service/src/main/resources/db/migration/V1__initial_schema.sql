-- ==============================================================================
-- Order Service — Initial Schema
-- ------------------------------------------------------------------------------
-- Flyway migration V1. Creates the tables for orders, order lines, and status
-- history.
--
-- TABLE NAMING
--   The schema is `orders` (created in Phase 0, File 6). The main table is
--   `purchase_order` (not `order`) because `order` is a reserved SQL word.
--   Column names like `order_id` are safe.
--
-- SNAPSHOT PATTERN
--   Order lines snapshot product details (SKU, name, unit price) at order
--   time. The product_id column references a product in the `products`
--   schema, but is NOT a foreign key — products can be deleted, and orders
--   must not break. Same for the shipping address: it's a snapshot, not a
--   reference to `customers.address`.
--
-- STATE MACHINE
--   Legal status transitions are enforced by the application (see
--   OrderStatus.java). The DB stores whatever status is written; the CHECK
--   constraint validates the value, not the transition.
--
-- ORDER NUMBER
--   Generated from a Postgres sequence. Format: ORD-YYYY-NNNNNNN.
--   Unique. Intended to be shared with customers; distinct from the UUID `id`.
-- ==============================================================================

-- ==============================================================================
-- Sequence: order_number_seq
-- ==============================================================================
-- Feeds order_number generation. The application reads nextval(), formats it
-- as ORD-YYYY-NNNNNNN, and stores the result.
--
-- The sequence is monotonic but not gap-free (rollbacks consume values). That
-- is acceptable: uniqueness is the only guarantee customers care about.
--
-- Phase 7 will add an annual reset via pg_cron (or an external scheduler).
-- ==============================================================================
CREATE SEQUENCE IF NOT EXISTS orders.order_number_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- ==============================================================================
-- Table: purchase_order
-- ==============================================================================
CREATE TABLE orders.purchase_order (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Human-readable identifier shared with customers. E.g., ORD-2025-0001234.
    order_number        varchar(30) NOT NULL UNIQUE,

    -- Customer reference. Not a foreign key (customers live in another schema
    -- and could be soft-deleted). The email snapshot below is authoritative
    -- for order emails.
    customer_id         uuid NOT NULL,
    customer_email      citext NOT NULL,

    -- Lifecycle status. Transitions are enforced by the application.
    status              varchar(20) NOT NULL DEFAULT 'DRAFT',

    -- Totals. Recomputed by the entity whenever lines change. Stored
    -- denormalized for query performance (aggregations, sorting).
    subtotal_amount     numeric(19,4) NOT NULL DEFAULT 0,
    tax_amount          numeric(19,4) NOT NULL DEFAULT 0,
    shipping_amount     numeric(19,4) NOT NULL DEFAULT 0,
    total_amount        numeric(19,4) NOT NULL DEFAULT 0,
    currency            char(3) NOT NULL,

    -- Shipping address snapshot. Copied from the customer's address at order
    -- time. Changes to the customer's address later do not affect this order.
    shipping_line1      varchar(200) NOT NULL,
    shipping_line2      varchar(200),
    shipping_city       varchar(100) NOT NULL,
    shipping_state      varchar(100),
    shipping_postal_code varchar(20) NOT NULL,
    shipping_country    char(2) NOT NULL,

    -- When the order was placed (transitioned from DRAFT to PLACED). Nullable
    -- while in DRAFT.
    placed_at           timestamptz,

    -- Free-form notes from the customer (e.g., delivery instructions).
    notes               text,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT order_status_valid
        CHECK (status IN ('DRAFT','PLACED','CONFIRMED','SHIPPED','DELIVERED','CANCELLED')),

    CONSTRAINT order_currency_iso
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT order_shipping_country_iso
        CHECK (shipping_country ~ '^[A-Z]{2}$'),

    CONSTRAINT order_amounts_non_negative
        CHECK (subtotal_amount >= 0
           AND tax_amount      >= 0
           AND shipping_amount >= 0
           AND total_amount    >= 0),

    CONSTRAINT order_total_matches_parts
        CHECK (total_amount = subtotal_amount + tax_amount + shipping_amount)
);

COMMENT ON TABLE orders.purchase_order IS
    'Orders. Aggregate root. Soft-statused via the status column.';
COMMENT ON COLUMN orders.purchase_order.order_number IS
    'Human-readable identifier (ORD-YYYY-NNNNNNN) shared with customers.';
COMMENT ON COLUMN orders.purchase_order.customer_id IS
    'Customer reference. No FK — customers live in the customers schema.';
COMMENT ON COLUMN orders.purchase_order.shipping_line1 IS
    'Shipping address snapshot. Not a reference to customers.address.';
COMMENT ON COLUMN orders.purchase_order.placed_at IS
    'Set when status transitions from DRAFT to PLACED.';

-- Common queries:
--   "orders for customer X" (customer's order list)
--   "orders with status Y" (ops dashboards)
--   "recent orders" (admin views)
CREATE INDEX idx_order_customer_created ON orders.purchase_order(customer_id, created_at DESC);
CREATE INDEX idx_order_status_created ON orders.purchase_order(status, created_at DESC);
CREATE INDEX idx_order_number ON orders.purchase_order(order_number);

-- ==============================================================================
-- Table: order_line
-- ==============================================================================
CREATE TABLE orders.order_line (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    order_id            uuid NOT NULL REFERENCES orders.purchase_order(id)
                            ON DELETE CASCADE,

    -- Reference to the product in the products schema. Not a FK — products
    -- can be deleted, and orders must not break. Details are snapshotted.
    product_id          uuid NOT NULL,
    product_sku         varchar(64) NOT NULL,
    product_name        varchar(300) NOT NULL,

    -- Unit price at order time.
    unit_price_amount   numeric(19,4) NOT NULL,
    unit_price_currency char(3) NOT NULL,

    quantity            int NOT NULL,

    -- Denormalized: unit_price * quantity. Recomputed by the entity.
    line_total          numeric(19,4) NOT NULL,

    created_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT order_line_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_line_unit_price_non_negative CHECK (unit_price_amount >= 0),
    CONSTRAINT order_line_total_non_negative CHECK (line_total >= 0),
    CONSTRAINT order_line_currency_iso CHECK (unit_price_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT order_line_total_matches
        CHECK (line_total = unit_price_amount * quantity)
);

COMMENT ON TABLE orders.order_line IS
    'Order lines. Each references a product (by id) but snapshots its details.';
COMMENT ON COLUMN orders.order_line.product_id IS
    'Product reference. No FK — products live in the products schema.';
COMMENT ON COLUMN orders.order_line.product_sku IS
    'Product SKU at order time. Snapshot, not a live reference.';
COMMENT ON COLUMN orders.order_line.unit_price_amount IS
    'Unit price at order time. Snapshot, not the current product price.';

CREATE INDEX idx_order_line_order ON orders.order_line(order_id);
CREATE INDEX idx_order_line_product ON orders.order_line(product_id);

-- ==============================================================================
-- Table: order_status_history
-- ==============================================================================
-- Append-only audit log of status transitions. The application inserts a row
-- on every transition. Updates and deletes should not occur.
CREATE TABLE orders.order_status_history (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    order_id        uuid NOT NULL REFERENCES orders.purchase_order(id)
                        ON DELETE CASCADE,

    -- The transition. from_status is NULL for the initial status.
    from_status     varchar(20),
    to_status       varchar(20) NOT NULL,

    -- Why the transition happened (free text). E.g., "payment succeeded",
    -- "customer cancelled".
    reason          varchar(500),

    -- Who triggered it. Username from JWT `preferred_username`, or `system`
    -- for automated transitions.
    changed_by      varchar(100) NOT NULL,

    changed_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT order_status_history_from_valid
        CHECK (from_status IS NULL OR from_status IN
            ('DRAFT','PLACED','CONFIRMED','SHIPPED','DELIVERED','CANCELLED')),

    CONSTRAINT order_status_history_to_valid
        CHECK (to_status IN
            ('DRAFT','PLACED','CONFIRMED','SHIPPED','DELIVERED','CANCELLED'))
);

COMMENT ON TABLE orders.order_status_history IS
    'Append-only audit log of order status transitions.';

CREATE INDEX idx_status_history_order ON orders.order_status_history(order_id, changed_at);