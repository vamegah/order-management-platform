-- ==============================================================================
-- Product Service — Initial Schema
-- ------------------------------------------------------------------------------
-- Flyway migration V1. Creates the tables that Product, Category, and
-- ProductImage entities map to.
--
-- IMMUTABILITY: once this migration has run on any database (dev, test, or
-- prod), it MUST NOT be edited. Schema changes go in a new migration file
-- (V2__..., V3__...). Flyway checksums each migration and refuses to start
-- if a previously-applied migration has changed.
--
-- ROLLBACK: Flyway's community edition doesn't support automatic rollback.
-- To undo a migration, write a companion `U1__...sql` (undo) migration. We
-- don't write undo migrations for Phase 1 — the cost of a bad schema is
-- handled by "restore from backup" (see File 10a).
-- ==============================================================================

-- ==============================================================================
-- Table: category
-- ------------------------------------------------------------------------------
-- Product categories, hierarchically structured. A category can have a
-- parent, enabling trees like "Electronics > Laptops > Gaming Laptops".
-- ==============================================================================
CREATE TABLE products.category (
    -- UUID primary key. gen_random_uuid() is from pgcrypto (see File 7).
    -- Using UUIDs rather than BIGSERIAL to avoid ID enumeration and to
    -- simplify future cross-instance replication.
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Parent category, or NULL for a root category.
    parent_id       uuid REFERENCES products.category(id)
                        ON DELETE RESTRICT,   -- don't delete a parent with children

    -- Display name. Human-readable, not unique.
    name            varchar(200) NOT NULL,

    -- URL-friendly slug. Case-insensitive unique via citext (installed in
    -- File 7). E.g., "gaming-laptops" not "Gaming Laptops".
    slug            citext NOT NULL UNIQUE,

    -- Optional description for category pages.
    description     text,

    -- Sort order among siblings (lower = earlier). Default 0.
    sort_order      integer NOT NULL DEFAULT 0,

    -- Auditing timestamps. Populated by JPA (see JpaConfig) or by DB triggers
    -- if the row is written outside the app (migrations, manual fixes).
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    -- Prevent a category from being its own parent (trivial cycle).
    -- Multi-level cycles require a recursive check, which is overkill here —
    -- the application enforces no-cycles through service-layer logic.
    CONSTRAINT category_not_self_parent
        CHECK (parent_id IS NULL OR parent_id <> id)
);

COMMENT ON TABLE products.category IS
    'Product categories, hierarchical via parent_id.';
COMMENT ON COLUMN products.category.slug IS
    'URL-friendly identifier. Case-insensitive unique.';
COMMENT ON COLUMN products.category.parent_id IS
    'Parent category. NULL for root categories.';

-- Index for the hierarchy: "find children of X" is the most common query.
CREATE INDEX idx_category_parent ON products.category(parent_id);

-- Index for lookups by slug (case-insensitive unique already has an implicit
-- index, but this makes the intent explicit).
CREATE INDEX idx_category_slug ON products.category(slug);

-- ==============================================================================
-- Table: product
-- ------------------------------------------------------------------------------
-- The main catalog. One row per SKU.
-- ==============================================================================
CREATE TABLE products.product (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Business identifier. Human-authored (e.g., "PROD-1234-XL"), unique.
    -- Case-insensitive: "SKU-001" and "sku-001" are the same product.
    sku             citext NOT NULL UNIQUE,

    -- Display fields.
    name            varchar(300) NOT NULL,
    description     text,

    -- Category. NULL = uncategorized (temporary state; should be rare).
    category_id     uuid REFERENCES products.category(id)
                        ON DELETE RESTRICT,

    -- Price stored as (amount, currency). Using numeric(19,4) rather than
    -- float: money must never be floating-point. 4 decimal places covers
    -- currencies with sub-cent precision.
    price_amount    numeric(19,4) NOT NULL,
    price_currency  char(3) NOT NULL,   -- ISO 4217, e.g., "USD", "EUR"

    -- Lifecycle. ACTIVE = visible to customers; INACTIVE = hidden (draft);
    -- DISCONTINUED = no longer sold but kept for historical orders.
    status          varchar(20) NOT NULL DEFAULT 'ACTIVE',

    -- Auditing.
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    -- Price must be non-negative. Zero is allowed (free products, placeholder).
    CONSTRAINT product_price_non_negative CHECK (price_amount >= 0),

    -- Currency must be a 3-letter ISO code (basic sanity; not validating
    -- against a currency list here — the application does that).
    CONSTRAINT product_currency_iso_4217 CHECK (price_currency ~ '^[A-Z]{3}$'),

    -- Status must be one of the known values.
    CONSTRAINT product_status_valid
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISCONTINUED'))
);

COMMENT ON TABLE products.product IS
    'Product catalog. One row per SKU. Soft-deleted via status.';
COMMENT ON COLUMN products.product.sku IS
    'Business identifier, case-insensitive unique.';
COMMENT ON COLUMN products.product.price_amount IS
    'Price in the currency below. numeric(19,4) — never use float for money.';
COMMENT ON COLUMN products.product.price_currency IS
    'ISO 4217 currency code (e.g., USD, EUR).';
COMMENT ON COLUMN products.product.status IS
    'ACTIVE (visible), INACTIVE (draft), DISCONTINUED (historical).';

-- Index for the most common query: list products by category.
CREATE INDEX idx_product_category ON products.product(category_id);

-- Index for filter by status (find active products).
CREATE INDEX idx_product_status ON products.product(status);

-- Composite index for "active products in category X" — the most common
-- catalog listing query.
CREATE INDEX idx_product_category_status ON products.product(category_id, status);

-- Trigram index for fuzzy name search. Uses pg_trgm (installed in File 7).
-- Enables `WHERE name ILIKE '%wid%'` to use an index instead of a full scan.
-- The `gin_trgm_ops` operator class is from the pg_trgm extension.
CREATE INDEX idx_product_name_trgm ON products.product
    USING gin (name gin_trgm_ops);

-- ==============================================================================
-- Table: product_image
-- ------------------------------------------------------------------------------
-- Product images. A product can have multiple; the first by sort_order is
-- the "primary" image shown in listings.
-- ==============================================================================
CREATE TABLE products.product_image (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Owning product. ON DELETE CASCADE: images are meaningless without
    -- their product, so we don't retain them after product deletion.
    -- (Note: products are soft-deleted, so this rarely fires. It's a
    -- safety net for hard deletes.)
    product_id      uuid NOT NULL REFERENCES products.product(id)
                        ON DELETE CASCADE,

    -- Image URL (typically points to object storage, not stored here).
    url             text NOT NULL,

    -- Alt text for accessibility and SEO.
    alt_text        varchar(500),

    -- Order among the product's images. Lower = shown first.
    sort_order      integer NOT NULL DEFAULT 0,

    created_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE products.product_image IS
    'Images associated with a product. Ordered by sort_order.';

-- Index for "list images of product X".
CREATE INDEX idx_product_image_product ON products.product_image(product_id, sort_order);