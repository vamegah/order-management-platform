-- ==============================================================================
-- Order Management Platform — Database Initialisation
-- ------------------------------------------------------------------------------
-- FILENAME : 01-schemas.sql
-- RUNS     : Once, on first container boot (when PGDATA is empty)
-- LOCATION : /docker-entrypoint-initdb.d/01-schemas.sql (inside the container)
-- ORDER    : Lexicographic — runs before 02-extensions.sql
--
-- WHAT THIS SCRIPT DOES
--   1. Configures database-level defaults (timezone, search_path behavior,
--      revoking PUBLIC schema-creation rights).
--   2. Creates one schema per bounded context.
--   3. Creates one role per service, owning its schema, with least privilege.
--   4. Grants the minimum privileges required for each role to function.
--   5. Sets per-role defaults (statement_timeout, idle_in_transaction_timeout).
--
-- WHAT THIS SCRIPT DOES NOT DO
--   - Create the `omp` database — the container's POSTGRES_DB env var does it.
--   - Create the superuser — the container's POSTGRES_USER env var does it.
--   - Create tables — Flyway migrations do that, per service, in Phase 1+.
--   - Create extensions — that's 02-extensions.sql (runs next).
--   - Set passwords — those come from Docker secrets (see File 2b). This
--     script creates roles WITHOUT passwords; the compose overlay or an
--     ops script sets them via ALTER ROLE at deploy time.
--
-- SAFETY
--   - Every CREATE is IF NOT EXISTS or guarded by a DO block.
--   - Re-running this script on an existing database is a no-op.
--   - No DROP statements, ever. If you need to reset, use `docker compose
--     down -v` — that's what it's for.
--
-- CONVENTIONS
--   - Schema names:  snake_case, plural, no prefix (e.g. `orders`, `customers`).
--   - Role names:    <schema>_svc (e.g. `orders_svc`, `customers_svc`).
--   - No passwords in this file. Ever. See "SAFETY" above.
--   - UTF-8 throughout. No BOM. LF line endings.
--
-- ==============================================================================

-- ==============================================================================
-- SECTION 1 — DATABASE-LEVEL CONFIGURATION
-- ------------------------------------------------------------------------------
-- These settings apply to the `omp` database. Some require superuser (which
-- we have here — init scripts run as the container's POSTGRES_USER).
-- ==============================================================================

-- Ensure the database timezone is UTC. All application timestamps are stored
-- as UTC; clients convert to local time. This is non-negotiable for a system
-- that will span multiple regions.
ALTER DATABASE omp SET timezone TO 'UTC';

-- Prefer ISO date formats in text output (e.g. `2024-11-15` instead of
-- `11/15/2024`). This makes logs and ad-hoc queries unambiguous.
ALTER DATABASE omp SET datestyle TO 'ISO, MDY';

-- Set the default text search configuration to English. Phase 1+ product
-- and customer search will use this.
ALTER DATABASE omp SET default_text_search_config TO 'pg_catalog.english';

-- Enforce standard SQL string handling. Empty strings are distinct from NULL,
-- which is what Java/JPA expects. This is the default in Postgres 16 but we
-- set it explicitly to prevent future surprises if the default changes.
ALTER DATABASE omp SET standard_conforming_strings TO on;

-- Revoke PUBLIC's ability to create objects in the `public` schema.
-- We don't use the `public` schema for application data (each service has
-- its own schema), but by default any role can create tables in `public`.
-- Locking this down prevents accidental namespace pollution.
--
-- NOTE: PostgreSQL 15+ already restricts this. We do it explicitly anyway
-- for clarity and to support eventual downgrades to PG14 in test environments.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Revoke PUBLIC's ability to connect to the database by default.
-- Roles must be explicitly granted CONNECT. This is defense in depth: even
-- if a role is created without a specific GRANT, it cannot connect.
-- (We re-grant CONNECT to each service role in section 3.)
REVOKE ALL ON DATABASE omp FROM PUBLIC;

-- Allow roles to be granted the ability to connect, but only via explicit
-- GRANT. The container's superuser obviously retains full access.
GRANT CONNECT ON DATABASE omp TO CURRENT_USER;

-- ==============================================================================
-- SECTION 2 — SCHEMAS
-- ------------------------------------------------------------------------------
-- One schema per bounded context. Schemas are named after the service that
-- owns them (snake_case, plural). The role that owns the schema is created
-- in section 3.
--
-- WHY THESE SCHEMAS
--   orders        — purchase orders, line items, order status history
--   customers     — customer profiles, addresses, preferences
--   products      — product catalog, categories, pricing
--   inventory     — stock levels, reservations, warehouse locations
--   payments      — payment intents, transactions, refunds
--   notifications — outbound webhooks, delivery attempts, subscriptions
--   keycloak      — Keycloak's internal tables (realm, users, clients, etc.)
--   reporting     — read-only materialised views spanning multiple services
--                   (owned by reporting_svc, populated by ETL, never written
--                    by application code)
--
-- WHY NOT `public`
--   The `public` schema is a legacy Postgres default. Using it for
--   application data causes namespace collisions and makes permissions
--   harder to reason about. We keep it empty (and locked down above).
-- ==============================================================================

CREATE SCHEMA IF NOT EXISTS orders;
COMMENT ON SCHEMA orders IS
    'Owned by order-service. Tables for orders and order lifecycle.';

CREATE SCHEMA IF NOT EXISTS customers;
COMMENT ON SCHEMA customers IS
    'Owned by customer-service. Customer profiles and preferences.';

CREATE SCHEMA IF NOT EXISTS products;
COMMENT ON SCHEMA products IS
    'Owned by product-service. Product catalog and pricing.';

CREATE SCHEMA IF NOT EXISTS inventory;
COMMENT ON SCHEMA inventory IS
    'Owned by inventory-service. Stock levels and reservations.';

CREATE SCHEMA IF NOT EXISTS payments;
COMMENT ON SCHEMA payments IS
    'Owned by payment-service. Payment intents, transactions, refunds.';

CREATE SCHEMA IF NOT EXISTS notifications;
COMMENT ON SCHEMA notifications IS
    'Owned by notification-service. Webhook subscriptions and delivery log.';

CREATE SCHEMA IF NOT EXISTS keycloak;
COMMENT ON SCHEMA keycloak IS
    'Owned by Keycloak. Realm, users, clients, sessions. Not for app use.';

CREATE SCHEMA IF NOT EXISTS reporting;
COMMENT ON SCHEMA reporting IS
    'Read-only cross-service views. Populated by ETL. Never written by apps.';

-- ==============================================================================
-- SECTION 3 — ROLES
-- ------------------------------------------------------------------------------
-- One role per service. Roles are created WITHOUT passwords here. Passwords
-- are set at deploy time via ALTER ROLE, using values from Docker secrets.
--
-- WHY NO PASSWORDS HERE
--   1. This file is committed to git. Passwords must never be in git.
--   2. The same init script runs in dev and prod. Dev has different secrets.
--   3. Separating "role exists" from "role can authenticate" is good practice:
--      a role with no password cannot log in, even if accidentally granted
--      to an attacker.
--
-- HOW PASSWORDS ARE SET
--   Dev : An ops script runs after `docker compose up` and executes
--         `ALTER ROLE x WITH PASSWORD '...'` for each role. Passwords come
--         from .env. This will be File 6a (a companion shell script).
--   Prod: The deploy pipeline reads secrets from /etc/omp/secrets/ and runs
--         the same ALTER ROLE statements via `psql`.
--
-- WHY THE DO BLOCK
--   Postgres has no `CREATE ROLE IF NOT EXISTS`. The idiomatic workaround is
--   a DO block that checks pg_roles first. This keeps the script idempotent.
--
-- ROLE NAMING
--   <schema>_svc — the service role that owns and uses the schema.
--   reporting_svc — special: read-only role used by reporting and ETL.
--   flyway      — shared migration role used by all Flyway migrations.
--                 Flyway runs as this role, not as each service role. This
--                 centralises DDL privileges and audit trail.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- Service roles
-- ------------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orders_svc') THEN
        CREATE ROLE orders_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE orders_svc IS
    'Application role for order-service. Owns the `orders` schema.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'customers_svc') THEN
        CREATE ROLE customers_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE customers_svc IS
    'Application role for customer-service. Owns the `customers` schema.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'products_svc') THEN
        CREATE ROLE products_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE products_svc IS
    'Application role for product-service. Owns the `products` schema.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_svc') THEN
        CREATE ROLE inventory_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE inventory_svc IS
    'Application role for inventory-service. Owns the `inventory` schema.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payments_svc') THEN
        CREATE ROLE payments_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE payments_svc IS
    'Application role for payment-service. Owns the `payments` schema.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notifications_svc') THEN
        CREATE ROLE notifications_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE notifications_svc IS
    'Application role for notification-service. Owns the `notifications` schema.';

-- ------------------------------------------------------------------------------
-- Keycloak role
-- ------------------------------------------------------------------------------
-- Keycloak connects as its own role, NOT as orders_svc or the superuser.
-- The Keycloak container's env vars (File 2a) point KC_DB_USERNAME at this
-- role in production. In dev, it currently uses the superuser for simplicity.
-- Phase 1 will tighten this to use keycloak_svc.
-- ------------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak_svc') THEN
        CREATE ROLE keycloak_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE keycloak_svc IS
    'Application role for Keycloak. Owns the `keycloak` schema.';

-- ------------------------------------------------------------------------------
-- Reporting role (read-only across schemas)
-- ------------------------------------------------------------------------------
-- Used by BI tools, ad-hoc analysts, and the ETL process that populates the
-- `reporting` schema. Has SELECT on every service schema but no write access
-- anywhere except the `reporting` schema it owns.
-- ------------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reporting_svc') THEN
        CREATE ROLE reporting_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE reporting_svc IS
    'Read-only role for reporting and ETL. Owns the `reporting` schema.';

-- ------------------------------------------------------------------------------
-- Flyway role (shared migration runner)
-- ------------------------------------------------------------------------------
-- All Flyway migrations across all services run as `flyway`, not as the
-- per-service roles. This centralises DDL privileges, simplifies audit
-- (every schema change is by one role), and prevents a compromised service
-- role from altering its own schema outside of a migration.
--
-- The role can CREATE and ALTER in every service schema. It CANNOT read or
-- write table data (no DML). This is enforced by not granting DML privileges.
-- ------------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'flyway') THEN
        CREATE ROLE flyway LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
COMMENT ON ROLE flyway IS
    'Shared migration role. Owns all DDL across service schemas. No DML.';

-- ==============================================================================
-- SECTION 4 — SCHEMA OWNERSHIP AND PRIVILEGES
-- ------------------------------------------------------------------------------
-- Now that schemas and roles exist, wire them together with the minimum
-- privileges each role needs.
--
-- PRIVILEGE PHILOSOPHY
--   Each service role owns its schema. "Owning" a schema means:
--     - It can CREATE tables, indexes, sequences, functions in the schema.
--     - It can ALTER or DROP objects it created.
--     - It automatically has USAGE and CREATE on the schema.
--     - It can GRANT privileges on its objects to other roles.
--
--   Ownership is set via ALTER SCHEMA ... OWNER TO ....
--
--   The schema owner is NOT automatically granted access to OTHER schemas.
--   That requires explicit GRANTs, done in section 5.
-- ==============================================================================

ALTER SCHEMA orders        OWNER TO orders_svc;
ALTER SCHEMA customers     OWNER TO customers_svc;
ALTER SCHEMA products      OWNER TO products_svc;
ALTER SCHEMA inventory     OWNER TO inventory_svc;
ALTER SCHEMA payments      OWNER TO payments_svc;
ALTER SCHEMA notifications OWNER TO notifications_svc;
ALTER SCHEMA keycloak      OWNER TO keycloak_svc;
ALTER SCHEMA reporting     OWNER TO reporting_svc;

-- Revoke PUBLIC access to every application schema. By default, PUBLIC has
-- USAGE on the `public` schema only, but on a shared instance with many
-- schemas we want to be explicit: no role can see any schema unless
-- specifically granted.
REVOKE ALL ON SCHEMA orders        FROM PUBLIC;
REVOKE ALL ON SCHEMA customers     FROM PUBLIC;
REVOKE ALL ON SCHEMA products      FROM PUBLIC;
REVOKE ALL ON SCHEMA inventory     FROM PUBLIC;
REVOKE ALL ON SCHEMA payments      FROM PUBLIC;
REVOKE ALL ON SCHEMA notifications FROM PUBLIC;
REVOKE ALL ON SCHEMA keycloak      FROM PUBLIC;
REVOKE ALL ON SCHEMA reporting     FROM PUBLIC;

-- Grant each service role CONNECT on the database. Without this, the role
-- cannot open a connection at all (we revoked PUBLIC's CONNECT above).
GRANT CONNECT ON DATABASE omp TO orders_svc;
GRANT CONNECT ON DATABASE omp TO customers_svc;
GRANT CONNECT ON DATABASE omp TO products_svc;
GRANT CONNECT ON DATABASE omp TO inventory_svc;
GRANT CONNECT ON DATABASE omp TO payments_svc;
GRANT CONNECT ON DATABASE omp TO notifications_svc;
GRANT CONNECT ON DATABASE omp TO keycloak_svc;
GRANT CONNECT ON DATABASE omp TO reporting_svc;
GRANT CONNECT ON DATABASE omp TO flyway;

-- ==============================================================================
-- SECTION 5 — CROSS-SCHEMA READ ACCESS
-- ------------------------------------------------------------------------------
-- Some roles need to read from schemas they don't own:
--
--   reporting_svc — reads every service schema for BI/analytics.
--   orders_svc    — needs to look up customer and product info when
--                   rendering an order (a legitimate read).
--   payments_svc  — needs to read order totals when verifying payment
--                   amounts.
--
-- DESIGN CHOICE
--   We grant SELECT (never INSERT/UPDATE/DELETE) on specific schemas. The
--   grant is on the schema, not on individual tables — Postgres supports
--   `GRANT SELECT ON ALL TABLES IN SCHEMA ...`. This is combined with a
--   DEFAULT PRIVILEGES rule (section 6) so future tables created by Flyway
--   are automatically readable.
--
-- TRADE-OFF
--   Schema-level SELECT is coarser than table-level. A service role can read
--   any table in the granted schema, including ones it doesn't logically
--   need. This is acceptable for the current architecture; if a specific
--   table becomes sensitive (e.g. PII in customers), we can REVOKE at the
--   table level and re-GRANT to specific roles.
--
-- NOTE ON BOUNDED CONTEXTS
--   Cross-schema reads blur the "each service owns its data" model. In a
--   pure microservices architecture, order-service would call customer-
--   service via REST/gRPC rather than joining on its tables. We do BOTH
--   here: cross-schema reads are allowed for reporting, but business logic
--   in Phase 1+ uses service-to-service calls. The reporting schema is the
--   escape hatch for analytics that need cross-context joins.
-- ==============================================================================

-- reporting_svc: read every service schema
GRANT USAGE ON SCHEMA orders        TO reporting_svc;
GRANT USAGE ON SCHEMA customers     TO reporting_svc;
GRANT USAGE ON SCHEMA products      TO reporting_svc;
GRANT USAGE ON SCHEMA inventory     TO reporting_svc;
GRANT USAGE ON SCHEMA payments      TO reporting_svc;
GRANT USAGE ON SCHEMA notifications TO reporting_svc;

GRANT SELECT ON ALL TABLES IN SCHEMA orders        TO reporting_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA customers     TO reporting_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA products      TO reporting_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA inventory     TO reporting_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA payments      TO reporting_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA notifications TO reporting_svc;

-- orders_svc: read customers and products (for order rendering)
GRANT USAGE ON SCHEMA customers TO orders_svc;
GRANT USAGE ON SCHEMA products  TO orders_svc;

GRANT SELECT ON ALL TABLES IN SCHEMA customers TO orders_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA products  TO orders_svc;

-- payments_svc: read orders (for payment amount verification)
GRANT USAGE ON SCHEMA orders TO payments_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA orders TO payments_svc;

-- flyway: needs USAGE on every schema it migrates
GRANT USAGE, CREATE ON SCHEMA orders        TO flyway;
GRANT USAGE, CREATE ON SCHEMA customers     TO flyway;
GRANT USAGE, CREATE ON SCHEMA products      TO flyway;
GRANT USAGE, CREATE ON SCHEMA inventory     TO flyway;
GRANT USAGE, CREATE ON SCHEMA payments      TO flyway;
GRANT USAGE, CREATE ON SCHEMA notifications TO flyway;
GRANT USAGE, CREATE ON SCHEMA keycloak      TO flyway;
GRANT USAGE, CREATE ON SCHEMA reporting     TO flyway;

-- ==============================================================================
-- SECTION 6 — DEFAULT PRIVILEGES
-- ------------------------------------------------------------------------------
-- The GRANTs in section 5 only apply to tables that ALREADY EXIST. When
-- Flyway creates new tables in Phase 1+, those tables will NOT automatically
-- be readable by reporting_svc or the cross-reading service roles.
--
-- DEFAULT PRIVILEGES fix this: they tell Postgres "any future table created
-- by role X in schema Y should automatically grant Z to role W."
--
-- The role that CREATES the table is what matters. Since all tables are
-- created by `flyway`, we set default privileges FOR ROLE flyway.
--
-- WHY THIS MATTERS
--   Without this section, every Phase 1+ migration would need to remember
--   to GRANT SELECT to reporting_svc. That's exactly the kind of thing
--   people forget. Default privileges make it automatic.
-- ==============================================================================

-- Future tables created by flyway in service schemas → readable by reporting_svc
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA orders
    GRANT SELECT ON TABLES TO reporting_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA customers
    GRANT SELECT ON TABLES TO reporting_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA products
    GRANT SELECT ON TABLES TO reporting_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA inventory
    GRANT SELECT ON TABLES TO reporting_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA payments
    GRANT SELECT ON TABLES TO reporting_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA notifications
    GRANT SELECT ON TABLES TO reporting_svc;

-- Future tables in customers/products → readable by orders_svc
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA customers
    GRANT SELECT ON TABLES TO orders_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA products
    GRANT SELECT ON TABLES TO orders_svc;

-- Future tables in orders → readable by payments_svc
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA orders
    GRANT SELECT ON TABLES TO payments_svc;

-- Future sequences created by flyway → usable by the owning service role
-- (Services need USAGE on sequences to call nextval() for auto-increment IDs.)
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA orders
    GRANT USAGE, SELECT ON SEQUENCES TO orders_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA customers
    GRANT USAGE, SELECT ON SEQUENCES TO customers_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA products
    GRANT USAGE, SELECT ON SEQUENCES TO products_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA inventory
    GRANT USAGE, SELECT ON SEQUENCES TO inventory_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA payments
    GRANT USAGE, SELECT ON SEQUENCES TO payments_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA notifications
    GRANT USAGE, SELECT ON SEQUENCES TO notifications_svc;

-- Future functions created by flyway → executable by the owning service role
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA orders
    GRANT EXECUTE ON FUNCTIONS TO orders_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA customers
    GRANT EXECUTE ON FUNCTIONS TO customers_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA products
    GRANT EXECUTE ON FUNCTIONS TO products_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA inventory
    GRANT EXECUTE ON FUNCTIONS TO inventory_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA payments
    GRANT EXECUTE ON FUNCTIONS TO payments_svc;
ALTER DEFAULT PRIVILEGES FOR ROLE flyway IN SCHEMA notifications
    GRANT EXECUTE ON FUNCTIONS TO notifications_svc;

-- ==============================================================================
-- SECTION 7 — PER-ROLE SESSION DEFAULTS
-- ------------------------------------------------------------------------------
-- Sensible defaults for every application role. These are applied per-role
-- so they only affect that role's sessions — they don't spill over into
-- superuser sessions or other roles.
--
-- statement_timeout
--   Kill any query that runs longer than 30 seconds. Protects against
--   runaway queries, unindexed joins on large tables, and accidental
--   cartesian products. In production, tune per-service based on real
--   query profiles.
--
-- idle_in_transaction_session_timeout
--   Kill any session that holds a transaction open for more than 60 seconds
--   without doing anything. Prevents connection pool exhaustion caused by
--   stuck clients.
--
-- lock_timeout
--   Fail fast (5 seconds) if a query can't acquire a lock. Prevents a
--   stuck transaction from blocking DDL migrations for minutes.
--
-- log_min_duration_statement
--   Log any query slower than 1 second. Feeds into observability; useful
--   for finding slow queries without a profiler.
-- ==============================================================================

ALTER ROLE orders_svc SET statement_timeout = '30s';
ALTER ROLE orders_svc SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE orders_svc SET lock_timeout = '5s';
ALTER ROLE orders_svc SET log_min_duration_statement = '1000';

ALTER ROLE customers_svc SET statement_timeout = '30s';
ALTER ROLE customers_svc SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE customers_svc SET lock_timeout = '5s';
ALTER ROLE customers_svc SET log_min_duration_statement = '1000';

ALTER ROLE products_svc SET statement_timeout = '30s';
ALTER ROLE products_svc SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE products_svc SET lock_timeout = '5s';
ALTER ROLE products_svc SET log_min_duration_statement = '1000';

ALTER ROLE inventory_svc SET statement_timeout = '30s';
ALTER ROLE inventory_svc SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE inventory_svc SET lock_timeout = '5s';
ALTER ROLE inventory_svc SET log_min_duration_statement = '1000';

ALTER ROLE payments_svc SET statement_timeout = '30s';
ALTER ROLE payments_svc SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE payments_svc SET lock_timeout = '5s';
ALTER ROLE payments_svc SET log_min_duration_statement = '1000';

ALTER ROLE notifications_svc SET statement_timeout = '30s';
ALTER ROLE notifications_svc SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE notifications_svc SET lock_timeout = '5s';
ALTER ROLE notifications_svc SET log_min_duration_statement = '1000';

ALTER ROLE keycloak_svc SET statement_timeout = '60s';
ALTER ROLE keycloak_svc SET idle_in_transaction_session_timeout = '120s';
ALTER ROLE keycloak_svc SET lock_timeout = '10s';

-- reporting_svc gets a much longer statement_timeout because analytics
-- queries legitimately run for minutes. This is deliberate: the reporting
-- role is allowed to do expensive things the application roles are not.
ALTER ROLE reporting_svc SET statement_timeout = '600s';
ALTER ROLE reporting_svc SET idle_in_transaction_session_timeout = '300s';
ALTER ROLE reporting_svc SET lock_timeout = '10s';
ALTER ROLE reporting_svc SET log_min_duration_statement = '5000';

-- flyway gets no statement_timeout. Migrations can legitimately run for
-- minutes (adding indexes to large tables). Timeouts here would cause
-- half-applied migrations, which is far worse than a slow migration.
ALTER ROLE flyway SET idle_in_transaction_session_timeout = '0';
ALTER ROLE flyway SET statement_timeout = '0';

-- ==============================================================================
-- SECTION 8 — SUMMARY
-- ------------------------------------------------------------------------------
-- After this script runs, the database has:
--
--   Schemas:   orders, customers, products, inventory, payments,
--              notifications, keycloak, reporting
--
--   Roles:     orders_svc, customers_svc, products_svc, inventory_svc,
--              payments_svc, notifications_svc, keycloak_svc,
--              reporting_svc, flyway
--
--   Ownership: each *_svc role owns its matching schema
--
--   Access:    * reporting_svc can read every service schema
--              * orders_svc can read customers and products
--              * payments_svc can read orders
--              * flyway can DDL in every schema
--              * No role can read any schema it wasn't explicitly granted
--
--   Defaults:  * statement_timeout, idle_in_transaction_session_timeout,
--                lock_timeout set per-role
--              * Future tables/sequences/functions auto-granted to the
--                appropriate service role
--
-- WHAT'S STILL MISSING (set by later scripts or at deploy time):
--   * Extensions (pgcrypto, pg_stat_statements, etc.) — 02-extensions.sql
--   * Passwords for every *_svc role — set via ALTER ROLE at deploy time
--   * Tables — created by Flyway migrations in Phase 1+
--
-- ==============================================================================
-- END OF 01-schemas.sql
-- ==============================================================================