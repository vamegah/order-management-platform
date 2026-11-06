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

-- Schemas
CREATE SCHEMA IF NOT EXISTS products;
CREATE SCHEMA IF NOT EXISTS customers;
CREATE SCHEMA IF NOT EXISTS orders;

-- Role for the customer service
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'customers_svc') THEN
        CREATE ROLE customers_svc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

GRANT ALL ON SCHEMA customers TO customers_svc;
GRANT CONNECT ON DATABASE omp TO customers_svc;