-- ==============================================================================
-- Customer Service — Initial Schema
-- ------------------------------------------------------------------------------
-- Flyway migration V1. Creates the tables that Customer, Address, and
-- CustomerPreferences entities map to.
--
-- IMMUTABILITY: once applied to any database, this migration MUST NOT be
-- edited. Changes go in a new migration.
--
-- DOMAIN NOTES
--   - Email is the business identity. Case-insensitive unique via citext.
--   - keycloak_sub links a customer to a Keycloak user (JWT `sub` claim).
--     Nullable: customers may be imported before they register.
--   - A customer has zero or more addresses. Exactly one may be flagged
--     is_default per address type (enforced by partial unique index).
--   - Preferences are 1:1 with customer.
-- ==============================================================================

-- ==============================================================================
-- Table: customer
-- ==============================================================================
CREATE TABLE customers.customer (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Business identity. Case-insensitive unique.
    email           citext NOT NULL UNIQUE,

    -- Keycloak `sub` claim. Links this customer to an authenticated user.
    -- Nullable: a customer may exist before registering (imported records).
    -- Unique when present (a Keycloak user maps to at most one customer).
    keycloak_sub    varchar(255) UNIQUE,

    first_name      varchar(100) NOT NULL,
    last_name       varchar(100) NOT NULL,

    -- Optional contact. Stored as provided; normalization is a future migration.
    phone           varchar(40),

    -- Optional. Some customers provide it (loyalty programs); most don't.
    date_of_birth   date,

    -- Lifecycle. ACTIVE = normal, INACTIVE = soft-deleted, SUSPENDED = blocked.
    status          varchar(20) NOT NULL DEFAULT 'ACTIVE',

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT customer_status_valid
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),

    -- Basic email format check. Application does the full RFC 5322 validation.
    CONSTRAINT customer_email_basic_format
        CHECK (email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$')
);

COMMENT ON TABLE customers.customer IS
    'Customer profiles. Business identity is email; soft-deleted via status.';
COMMENT ON COLUMN customers.customer.keycloak_sub IS
    'Keycloak `sub` claim. Links an authenticated user to this customer.';
COMMENT ON COLUMN customers.customer.status IS
    'ACTIVE (normal), INACTIVE (soft-deleted), SUSPENDED (blocked).';

CREATE INDEX idx_customer_status ON customers.customer(status);
CREATE INDEX idx_customer_keycloak_sub ON customers.customer(keycloak_sub)
    WHERE keycloak_sub IS NOT NULL;

-- Trigram indexes for fuzzy name search. Uses pg_trgm (File 7).
CREATE INDEX idx_customer_first_name_trgm ON customers.customer
    USING gin (first_name gin_trgm_ops);
CREATE INDEX idx_customer_last_name_trgm ON customers.customer
    USING gin (last_name gin_trgm_ops);

-- Composite index for a common query: "active customers sorted by created_at".
CREATE INDEX idx_customer_status_created ON customers.customer(status, created_at DESC);

-- ==============================================================================
-- Table: address
-- ==============================================================================
CREATE TABLE customers.address (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    customer_id     uuid NOT NULL REFERENCES customers.customer(id)
                        ON DELETE CASCADE,

    -- Type distinguishes billing vs. shipping. BOTH covers the common case
    -- where a customer uses one address for both.
    address_type    varchar(20) NOT NULL DEFAULT 'BOTH',

    line1           varchar(200) NOT NULL,
    line2           varchar(200),
    city            varchar(100) NOT NULL,
    state           varchar(100),
    postal_code     varchar(20) NOT NULL,

    -- ISO 3166-1 alpha-2 country code. E.g., US, GB, DE.
    country         char(2) NOT NULL,

    -- At most one default per (customer, address_type). Enforced below.
    is_default      boolean NOT NULL DEFAULT false,

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT address_type_valid
        CHECK (address_type IN ('BILLING', 'SHIPPING', 'BOTH')),

    CONSTRAINT address_country_iso
        CHECK (country ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE customers.address IS
    'Customer addresses (billing, shipping). Multiple per customer.';
COMMENT ON COLUMN customers.address.is_default IS
    'Marks the default address for its type. Enforced by partial unique index.';

CREATE INDEX idx_address_customer ON customers.address(customer_id);

-- Partial unique index: at most one default address per (customer, type).
-- Postgres evaluates `WHERE` at insert/update time. Attempting to set two
-- addresses as default for the same type fails with a unique violation.
CREATE UNIQUE INDEX idx_address_default_unique
    ON customers.address(customer_id, address_type)
    WHERE is_default = true;

-- ==============================================================================
-- Table: customer_preferences
-- ==============================================================================
-- 1:1 with customer. Kept separate to keep the `customer` table narrow and
-- hot. Preferences are read on every order (currency, locale), rarely updated.
CREATE TABLE customers.customer_preferences (
    customer_id         uuid PRIMARY KEY REFERENCES customers.customer(id)
                            ON DELETE CASCADE,

    -- BCP 47 language tag. E.g., en-US, de-DE.
    locale              varchar(20) NOT NULL DEFAULT 'en-US',

    -- ISO 4217 currency code.
    currency            char(3) NOT NULL DEFAULT 'USD',

    marketing_opt_in    boolean NOT NULL DEFAULT false,
    notification_email  boolean NOT NULL DEFAULT true,
    notification_sms    boolean NOT NULL DEFAULT false,

    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT prefs_locale_format
        CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),

    CONSTRAINT prefs_currency_iso
        CHECK (currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE customers.customer_preferences IS
    'Per-customer preferences (1:1). Read on every order.';
COMMENT ON COLUMN customers.customer_preferences.locale IS
    'BCP 47 language tag. E.g., en-US.';
COMMENT ON COLUMN customers.customer_preferences.currency IS
    'ISO 4217 currency code. E.g., USD.';