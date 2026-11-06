-- ==============================================================================
-- Transactional Outbox — Table DDL
-- ------------------------------------------------------------------------------
-- This file is a TEMPLATE. Each service that produces events copies it into a
-- Flyway migration in its own schema. The table is created in the service's
-- schema (orders, inventory, payments), not in a shared schema.
--
-- WHY A TEMPLATE AND NOT A SHARED MIGRATION
--   Flyway migrations belong to the service that owns the schema. Sharing them
--   via classpath would require cross-schema write access, breaking the
--   schema-per-service boundary. Copying a small DDL fragment is acceptable.
--
-- SCHEMA PLACEHOLDER
--   The CREATE TABLE below has no schema qualifier. It lands in whichever schema
--   is the default for the connecting user (set via currentSchema in the JDBC
--   URL). For order-service that's `orders`; for inventory-service `inventory`;
--   for payment-service `payments`.
--
-- CLEANUP
--   Published rows are retained for 7 days by default (configurable) and then
--   deleted by a scheduled cleanup job in the drainer. This provides a short
--   audit trail without unbounded growth.
-- ==============================================================================

CREATE TABLE payments.outbox_event (
    -- Primary key. UUID so we never have a hot sequence and can generate IDs
    -- at the application side before the insert.
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The aggregate identifier (order ID, reservation ID, payment ID).
    -- Used as the Kafka partition key so events for one aggregate stay ordered.
    aggregate_id    varchar(255) NOT NULL,

    -- Broad category of the aggregate: ORDER, RESERVATION, PAYMENT.
    -- Enables filtering and per-aggregate-type reporting.
    aggregate_type  varchar(50) NOT NULL,

    -- The specific event type: ORDER_PLACED, RESERVATION_CREATED, etc.
    event_type      varchar(100) NOT NULL,

    -- Destination Kafka topic. Enables the drainer to be topic-agnostic.
    topic           varchar(255) NOT NULL,

    -- The serialized event payload (JSON). jsonb (not json, not text) so
    -- Postgres validates structure at insert time and stores it efficiently
    -- for operator inspection via psql.
    payload         jsonb NOT NULL,

    -- Lifecycle: PENDING (waiting for drainer), PUBLISHED (drained), FAILED
    -- (drainer gave up after N attempts; requires manual intervention).
    status          varchar(20) NOT NULL DEFAULT 'PENDING',

    -- When the row was inserted (same transaction as the state change).
    created_at      timestamptz NOT NULL DEFAULT now(),

    -- When the drainer successfully published. NULL while PENDING.
    published_at    timestamptz,

    -- Retry counter. Incremented each time the drainer attempts a publish
    -- and fails. Drainer gives up after a configurable threshold (default 10).
    attempts        integer NOT NULL DEFAULT 0,

    -- The last exception message, for operator debugging. NULL on success.
    last_error      text,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_outbox_attempts_non_negative CHECK (attempts >= 0)
);

COMMENT ON TABLE payments.outbox_event IS
    'Transactional outbox. Inserted in the same tx as the state change; drained to Kafka.';
COMMENT ON COLUMN payments.outbox_event.aggregate_id IS
    'Aggregate identifier. Also the Kafka partition key.';
COMMENT ON COLUMN payments.outbox_event.topic IS
    'Destination Kafka topic. Drainer is topic-agnostic.';
COMMENT ON COLUMN payments.outbox_event.payload IS
    'Serialized event payload as JSON.';
COMMENT ON COLUMN payments.outbox_event.status IS
    'PENDING, PUBLISHED, or FAILED (after retry exhaustion).';

-- Partial index for the drainer's hot query: find PENDING rows, oldest first.
-- Partial (WHERE status = 'PENDING') keeps the index small even when the table
-- has millions of PUBLISHED rows awaiting cleanup.
CREATE INDEX idx_outbox_pending
    ON payments.outbox_event (created_at)
    WHERE status = 'PENDING';

-- Index for the cleanup job: find PUBLISHED rows older than the retention
-- window. Same partial-index trick.
CREATE INDEX idx_outbox_published_cleanup
    ON payments.outbox_event (published_at)
    WHERE status = 'PUBLISHED';

-- Index for FAILED rows so operators can find them quickly.
CREATE INDEX idx_outbox_failed
    ON payments.outbox_event (created_at)
    WHERE status = 'FAILED';