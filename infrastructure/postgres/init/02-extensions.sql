-- ==============================================================================
-- Order Management Platform — Extension Installation
-- ------------------------------------------------------------------------------
-- FILENAME : 02-extensions.sql
-- RUNS     : Once, on first container boot (after 01-schemas.sql)
-- LOCATION : /docker-entrypoint-initdb.d/02-extensions.sql
-- ORDER    : Lexicographic — runs after 01-schemas.sql
--
-- WHAT THIS SCRIPT DOES
--   Installs Postgres extensions used across all services. Extensions are
--   installed into the `public` schema (which is locked down — no user tables,
--   no PUBLIC CREATE — so it acts as an extension namespace).
--
-- DEPENDENCIES
--   - 01-schemas.sql has already run (schemas and roles exist)
--   - postgresql.conf has `shared_preload_libraries` set correctly (File 8)
--     This is critical for pg_stat_statements to load.
--
-- WHAT THIS SCRIPT DOES NOT DO
--   - Create tables — Flyway migrations do that per-service.
--   - Configure pg_cron jobs — added in Phase 7 (cleanup jobs).
--   - Install optional extensions — see section 3 for the commented list.
--
-- MANAGED-POSTGRES CAVEAT
--   AWS RDS, GCP Cloud SQL, and Azure Database for PostgreSQL whitelist which
--   extensions can be installed. The extensions in section 1 are universally
--   available. Those in section 3 are not — check your provider's docs before
--   uncommenting them in production.
--
-- ==============================================================================

-- ==============================================================================
-- SECTION 1 — REQUIRED EXTENSIONS
-- ------------------------------------------------------------------------------
-- These are installed on every deployment. They are used by core application
-- functionality across multiple phases. Removing any of them would require
-- code changes in Phases 1–6.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- pgcrypto
-- ------------------------------------------------------------------------------
-- Provides cryptographic functions:
--   * gen_random_uuid()          — modern, standards-based UUID v4 generator.
--                                  Used as the default for every primary key
--                                  across all services. Replaces the older
--                                  uuid-ossp / gen_random_uuid from pg 13+
--                                  (which is actually from pgcrypto).
--   * digest(data, algo)         — SHA-256 hashing. Used for idempotency keys,
--                                  cache keys, and content addressing.
--   * hmac(data, key, algo)      — HMAC signing. Used in Phase 5 for webhook
--                                  signature generation AND verification.
--   * crypt(password, salt)      — bcrypt password hashing. Not for user
--                                  passwords (Keycloak does that), but useful
--                                  for service-level API keys stored hashed.
--   * gen_random_bytes(n)        — cryptographically secure random bytes.
--                                  Used for nonces, salts, and secrets.
--
-- INSTALLED FOR: orders (idempotency keys), payments (webhook signatures),
--                notifications (HMAC for outbound webhooks), every service
--                (UUID primary keys).
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;
COMMENT ON EXTENSION pgcrypto IS
    'Cryptographic functions: UUIDs, hashing, HMAC, random bytes. Required.';

-- ------------------------------------------------------------------------------
-- pg_stat_statements
-- ------------------------------------------------------------------------------
-- Collects execution statistics for every SQL statement:
--   * Number of calls, total execution time, rows returned
--   * Buffer hits/misses, temp file usage
--   * Plan information (in newer versions)
--
-- This is the single most valuable extension for performance tuning. It's how
-- you answer "which queries are slowest across the entire application?" without
-- adding any instrumentation to the application code.
--
-- EXPOSED TO: Prometheus via postgres_exporter (Phase 7). Dashboards in
--             Grafana will surface top-N slow queries per service.
--
-- PREREQUISITE: postgresql.conf must have
--                   shared_preload_libraries = 'pg_stat_statements'
--               Set by File 8. If that's missing, this CREATE fails with:
--                   ERROR: pg_stat_statements must be loaded via
--                          shared_preload_libraries
--
-- RESETTING: `SELECT pg_stat_statements_reset();` clears accumulated stats.
--            Useful after schema changes or index additions.
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
COMMENT ON EXTENSION pg_stat_statements IS
    'Query-level execution statistics. Required for observability.';

-- ------------------------------------------------------------------------------
-- pg_trgm
-- ------------------------------------------------------------------------------
-- Trigram-based text similarity and indexing. Enables:
--   * Fast `LIKE '%foo%'` and `ILIKE '%foo%'` searches via GIN/GiST indexes.
--     Without this, a leading wildcard forces a full table scan.
--   * `similarity(a, b)` — returns 0.0–1.0 similarity score.
--   * `%` operator — "is a similar to b" using an index.
--   * `word_similarity(a, b)` — fuzzy search for a word inside a string.
--
-- INSTALLED FOR: products (product name search), customers (name search),
--                orders (order lookup by partial reference).
--
-- USAGE (Phase 1+ migrations):
--   CREATE INDEX idx_products_name_trgm ON products.product
--       USING gin (name gin_trgm_ops);
--
--   SELECT * FROM products.product WHERE name ILIKE '%wid%';
--   -- Uses the GIN index; without pg_trgm, this is a seq scan.
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;
COMMENT ON EXTENSION pg_trgm IS
    'Trigram similarity and text search indexes. Required for fuzzy search.';

-- ------------------------------------------------------------------------------
-- citext
-- ------------------------------------------------------------------------------
-- Case-insensitive text type. `citext` compares equal regardless of case,
-- and index lookups work case-insensitively without `LOWER()` wrappers.
--
-- INSTALLED FOR: customers (email uniqueness — `victor@example.com` and
--                `Victor@Example.com` should be the same account).
--
-- USAGE:
--   CREATE TABLE customers.customer (
--       email citext UNIQUE NOT NULL,
--       ...
--   );
--
-- WITHOUT citext, you'd need:
--   CREATE UNIQUE INDEX ON customers.customer (LOWER(email));
--   -- and every query would need `WHERE LOWER(email) = LOWER(?)`
--   -- which is easy to forget and causes bugs.
--
-- TRADE-OFF: citext is slightly slower than text due to internal lowercasing,
-- but the correctness benefit far outweighs a few microseconds per row.
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS citext;
COMMENT ON EXTENSION citext IS
    'Case-insensitive text type. Required for email uniqueness.';

-- ==============================================================================
-- SECTION 2 — STRONGLY RECOMMENDED EXTENSIONS
-- ------------------------------------------------------------------------------
-- These are installed by default but could be removed if you have a specific
-- reason to. Their overhead is negligible in normal operation.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- btree_gin
-- ------------------------------------------------------------------------------
-- Adds GIN index support for scalar types (integer, text, timestamp, etc.).
-- Without this, GIN indexes only work on array/jsonb/tsvector columns. With
-- it, you can build a single GIN index over a mix of scalar and complex
-- columns, which is often the right shape for multi-column search.
--
-- INSTALLED FOR: products (composite search over name + category + tags),
--                orders (search over status + customer_id + created_at).
--
-- USAGE:
--   CREATE INDEX idx_product_search ON products.product
--       USING gin (name gin_trgm_ops, category, status);
--   -- One index serves queries filtering on any combination of these columns.
--
-- WHEN TO USE: When a table has many optional filters (a "faceted search"
-- pattern) and traditional btree indexes would need one index per combination.
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS btree_gin;
COMMENT ON EXTENSION btree_gin IS
    'GIN indexes over scalar types. Enables composite search indexes.';

-- ------------------------------------------------------------------------------
-- btree_gist
-- ------------------------------------------------------------------------------
-- GIST variant of the above. GIST supports range types, geometric types, and
-- exclusion constraints. btree_gist lets scalar columns participate in GIST
-- indexes and exclusion constraints.
--
-- INSTALLED FOR: inventory (exclusion constraint preventing overlapping
--                reservations of the same SKU in the same warehouse).
--
-- USAGE (Phase 3 migration):
--   CREATE TABLE inventory.reservation (
--       sku         text,
--       warehouse   text,
--       period      tstzrange,
--       EXCLUDE USING gist (
--           sku      WITH =,
--           warehouse WITH =,
--           period   WITH &&
--       )
--   );
--   -- Prevents two overlapping reservations for the same SKU in the same
--   -- warehouse. This is enforced at the DB level, not just in app code.
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS btree_gist;
COMMENT ON EXTENSION btree_gist IS
    'GIST indexes over scalar types. Enables exclusion constraints.';

-- ------------------------------------------------------------------------------
-- unaccent
-- ------------------------------------------------------------------------------
-- Removes diacritics (accents) from text. `café` → `cafe`, `Zürich` → `Zurich`.
-- Combined with pg_trgm, enables accent-insensitive search.
--
-- INSTALLED FOR: customers (search by name across international spellings),
--                products (search for `creme brulee` matches `crème brûlée`).
--
-- USAGE:
--   CREATE INDEX idx_product_name_unaccent ON products.product
--       USING gin (unaccent(lower(name)) gin_trgm_ops);
--
--   SELECT * FROM products.product
--       WHERE unaccent(lower(name)) LIKE unaccent(lower('%creme%'));
--
-- NOTE: `unaccent()` is not IMMUTABLE by default, which blocks it from being
-- used in index expressions directly. Workaround: create a wrapper IMMUTABLE
-- function, or use the `unaccent` alternative from the `pg_trgm` docs. We'll
-- handle this in Phase 1 when we actually create the index.
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS unaccent;
COMMENT ON EXTENSION unaccent IS
    'Diacritic removal for accent-insensitive search. Recommended.';

-- ==============================================================================
-- SECTION 3 — OPTIONAL EXTENSIONS (commented out)
-- ------------------------------------------------------------------------------
-- Not installed by default. Each has a specific use case that isn't needed
-- yet. Uncomment (and remove the COMMENT) when the use case arrives.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- uuid-ossp
-- ------------------------------------------------------------------------------
-- Older UUID generator. Superseded by pgcrypto's gen_random_uuid() for UUID
-- v4. Still useful if you need UUID v1 (time-based) or v5 (namespace+name),
-- which pgcrypto doesn't provide.
--
-- WHEN TO UNCOMMENT: If you need time-ordered UUIDs (v1) for better B-tree
-- index locality, or deterministic UUIDs (v5) derived from a namespace + name.
--
-- MODERN ALTERNATIVE: Consider `uuidv7()` from the `pg_uuidv7` extension,
-- which provides time-ordered UUIDs designed for B-tree friendliness. Not
-- in core Postgres yet as of PG16.
-- ------------------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- COMMENT ON EXTENSION "uuid-ossp" IS 'Alternative UUID generators. Optional.';

-- ------------------------------------------------------------------------------
-- postgres_fdw
-- ------------------------------------------------------------------------------
-- Foreign Data Wrapper. Lets one Postgres instance query tables in another
-- Postgres instance as if they were local.
--
-- USE CASE (future): When we split the reporting workload to a dedicated
-- Postgres instance, postgres_fdw lets reporting queries join across the
-- primary and reporting databases transparently.
--
-- USE CASE (now, if we wanted): The `reporting` schema could use postgres_fdw
-- to federate across the service schemas. But since they're all in one
-- database, plain cross-schema SELECT is simpler and faster. FDW adds value
-- only when the source is on a different host.
--
-- WHEN TO UNCOMMENT: When reporting moves to a dedicated instance.
-- ------------------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS postgres_fdw;
-- COMMENT ON EXTENSION postgres_fdw IS 'Cross-instance federated queries. Optional.';

-- ------------------------------------------------------------------------------
-- pg_partman
-- ------------------------------------------------------------------------------
-- Automated partition management for time-series tables. Creates new
-- partitions on schedule, drops old ones based on retention policy.
--
-- USE CASE (Phase 5+): High-volume tables like `notifications.webhook_delivery`
-- (one row per delivery attempt) or `payments.payment_event` (one row per
-- state transition) grow quickly. Partitioning by month keeps queries fast
-- and makes retention trivial (`DROP PARTITION` instead of `DELETE`).
--
-- WHEN TO UNCOMMENT: When a table is expected to exceed ~100M rows, or when
-- you need rolling retention with zero-downtime cleanup.
--
-- MANAGED-POSTGRES CAVEAT: Not available on all managed providers. AWS RDS
-- supports it; GCP Cloud SQL does not (as of late 2024).
-- ------------------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS pg_partman;
-- COMMENT ON EXTENSION pg_partman IS 'Automated partition management. Phase 5+.';

-- ------------------------------------------------------------------------------
-- pg_cron
-- ------------------------------------------------------------------------------
-- In-database job scheduler. Runs SQL on a cron schedule from within Postgres.
--
-- USE CASE (Phase 7): Cleanup jobs like "delete expired idempotency keys
-- older than 24 hours" or "archive delivered webhooks older than 30 days."
-- Running these in-database avoids a separate scheduler container.
--
-- ALTERNATIVE: A dedicated job runner (Quartz, Kubernetes CronJob, or an
-- external scheduler like Airflow) may be preferable if you want jobs to
-- be version-controlled alongside application code rather than embedded in
-- database state.
--
-- PREREQUISITE: Requires `shared_preload_libraries = 'pg_cron'` and a
-- dedicated `cron.database_name` config. File 8 does NOT enable it yet.
--
-- WHEN TO UNCOMMENT: Phase 7, alongside the cleanup jobs. Also add pg_cron
-- to shared_preload_libraries in postgresql.conf at that time.
-- ------------------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS pg_cron;
-- COMMENT ON EXTENSION pg_cron IS 'In-database cron scheduler. Phase 7.';

-- ------------------------------------------------------------------------------
-- pgvector
-- ------------------------------------------------------------------------------
-- Vector similarity search. Adds a `vector` type and indexes (IVFFlat, HNSW)
-- for approximate nearest-neighbor queries.
--
-- USE CASE (future): Product recommendations ("customers who bought X also
-- bought Y"), semantic search over product descriptions, or fraud detection
-- based on embedding similarity. Any of these would justify pgvector.
--
-- WHEN TO UNCOMMENT: If/when we add a recommendation engine or semantic
-- search. Not needed for Phases 1–6.
--
-- MANAGED-POSTGRES CAVEAT: AWS RDS and GCP Cloud SQL both support pgvector
-- now. Check version compatibility — HNSW indexes require pgvector 0.5+.
-- ------------------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS vector;
-- COMMENT ON EXTENSION vector IS 'Vector similarity search. Future.';

-- ==============================================================================
-- SECTION 4 — VERIFICATION
-- ------------------------------------------------------------------------------
-- After this script runs, verify the installed extensions:
-- ==============================================================================

DO $$
DECLARE
    required_extensions text[] := ARRAY[
        'pgcrypto',
        'pg_stat_statements',
        'pg_trgm',
        'citext',
        'btree_gin',
        'btree_gist',
        'unaccent'
    ];
    ext text;
    missing text[] := ARRAY[]::text[];
BEGIN
    FOREACH ext IN ARRAY required_extensions LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = ext) THEN
            missing := array_append(missing, ext);
        END IF;
    END LOOP;

    IF array_length(missing, 1) > 0 THEN
        RAISE EXCEPTION 'Required extensions missing after init: %', missing;
    END IF;

    RAISE NOTICE 'All required extensions installed successfully.';
END
$$;

-- ==============================================================================
-- SECTION 5 — SUMMARY
-- ------------------------------------------------------------------------------
-- After this script runs, the database has these extensions installed:
--
--   pgcrypto            — UUID generation, hashing, HMAC, random bytes
--   pg_stat_statements  — query-level statistics (requires preload)
--   pg_trgm             — trigram fuzzy text search
--   citext              — case-insensitive text (for email uniqueness)
--   btree_gin           — composite GIN indexes over scalar types
--   btree_gist          — exclusion constraints with scalar columns
--   unaccent            — diacritic removal for international search
--
-- Available but NOT installed (uncomment in section 3 when needed):
--
--   uuid-ossp          — alternative UUID generators (v1, v5)
--   postgres_fdw       — cross-instance federated queries
--   pg_partman         — automated partition management
--   pg_cron            — in-database job scheduling
--   pgvector           — vector similarity search
--
-- WHERE THEY LIVE
--   All extensions are installed into the `public` schema. Since File 6
--   revoked CREATE on `public` for PUBLIC and created no user tables there,
--   `public` acts as a dedicated extension namespace.
--
-- SEARCH PATH IMPLICATIONS
--   Application connections should set:
--       search_path = "<service_schema>, public"
--   e.g., order-service uses `search_path = orders, public`.
--   This lets `gen_random_uuid()` resolve from `public` (pgcrypto) while
--   `orders.order` resolves from `orders`.
--
--   This is configured per-service via the JDBC URL:
--       jdbc:postgresql://postgres:5432/omp?currentSchema=orders,public
--   Or via `SET search_path` after connection. Details in Phase 1.
--
-- ==============================================================================
-- END OF 02-extensions.sql
-- ==============================================================================