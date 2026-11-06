-- ==============================================================================
-- Test Postgres initialization
-- ------------------------------------------------------------------------------
-- Runs before Flyway migrations in integration tests. Creates the same
-- schemas, roles, and extensions that File 6 and File 7 create in the
-- dev/prod stack.
-- ==============================================================================

-- Extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- Schema for products
CREATE SCHEMA IF NOT EXISTS products;
CREATE SCHEMA IF NOT EXISTS customers;
CREATE SCHEMA IF NOT EXISTS orders;

--- orders_svc role
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orders_svc') THEN
        CREATE ROLE orders_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;
GRANT ALL ON SCHEMA orders TO orders_svc;
GRANT CONNECT ON DATABASE omp TO orders_svc;
-- The order_number_seq is created by V1 migration, so no explicit
-- sequence creation needed here.

-- Flyway runs as the superuser (omp_admin) in tests, so no additional
-- grants needed. In dev/prod, Flyway runs as its own role (see File 6).