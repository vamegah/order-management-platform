-- ==============================================================================
-- Notification Service — Outbound Webhook Schema
-- ------------------------------------------------------------------------------
-- Flyway migration V1. Creates the tables that back the outbound webhook
-- system.
--
-- The `notifications` schema and `notifications_svc` role were created in
-- Phase 0 (File 6). This migration only creates the tables inside the schema.
--
-- IMMUTABILITY: once applied, this migration must not be edited. Schema
-- changes go in new migrations.
-- ==============================================================================

-- ==============================================================================
-- Table: webhook_subscription
-- ------------------------------------------------------------------------------
-- A durable registration of an external URL that wants to receive webhook
-- notifications when certain event types occur.
-- ==============================================================================
CREATE TABLE notifications.webhook_subscription (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Owner: distinguishes customer-owned subscriptions from admin-owned.
    owner_type          varchar(20) NOT NULL,
    owner_id            uuid NOT NULL,

    -- Human-readable label. Useful when a customer has multiple subscriptions.
    name                varchar(200) NOT NULL,

    -- Target URL. Must be http(s). We do not require HTTPS in dev (localhost
    -- testing), but the docs strongly recommend HTTPS in production.
    target_url          text NOT NULL,

    -- The subscriber's HMAC secret. Used to sign outbound payloads. Stored
    -- in plaintext because we need to read it back to sign. Restricted to
    -- the notifications_svc role.
    secret              text NOT NULL,

    -- Event types this subscription wants. E.g., ['ORDER_PLACED',
    -- 'ORDER_SHIPPED']. GIN-indexed for fast `@>` queries.
    event_types         text[] NOT NULL,

    enabled             boolean NOT NULL DEFAULT true,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    -- Observability and circuit-breaker state.
    last_delivery_at    timestamptz,
    failure_count       integer NOT NULL DEFAULT 0,
    disabled_at         timestamptz,
    disabled_reason     varchar(200),

    CONSTRAINT chk_subscription_owner_type
        CHECK (owner_type IN ('customer', 'admin')),

    CONSTRAINT chk_subscription_url_scheme
        CHECK (target_url ~ '^https?://'),

    CONSTRAINT chk_subscription_event_types_not_empty
        CHECK (array_length(event_types, 1) IS NOT NULL
               AND array_length(event_types, 1) > 0),

    CONSTRAINT chk_subscription_secret_length
        CHECK (length(secret) >= 32),

    CONSTRAINT chk_subscription_failure_count_non_negative
        CHECK (failure_count >= 0)
);

COMMENT ON TABLE notifications.webhook_subscription IS
    'Durable outbound webhook subscriptions. One row per (owner, target URL).';
COMMENT ON COLUMN notifications.webhook_subscription.secret IS
    'HMAC secret for signing outbound payloads. Plaintext; restricted to notifications_svc.';
COMMENT ON COLUMN notifications.webhook_subscription.event_types IS
    'Array of event type constants this subscription receives.';

-- Find all subscriptions for an owner (customer dashboard query).
CREATE INDEX idx_subscription_owner
    ON notifications.webhook_subscription(owner_type, owner_id);

-- Find all enabled subscriptions (matcher query, partial index).
CREATE INDEX idx_subscription_enabled
    ON notifications.webhook_subscription(enabled)
    WHERE enabled = true;

-- Match subscriptions by event type (matcher query, GIN index).
CREATE INDEX idx_subscription_event_types
    ON notifications.webhook_subscription
    USING gin(event_types);

-- ==============================================================================
-- Table: webhook_delivery_attempt
-- ------------------------------------------------------------------------------
-- One row per HTTP attempt. A single logical delivery (one event to one
-- subscriber) produces multiple attempts when retried.
-- ==============================================================================
CREATE TABLE notifications.webhook_delivery_attempt (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Stable identifier of the logical delivery. Same value across retries.
    -- Included in the X-Webhook-Delivery-Id header so subscribers can dedupe.
    delivery_id             uuid NOT NULL,

    subscription_id         uuid NOT NULL
                                REFERENCES notifications.webhook_subscription(id)
                                ON DELETE CASCADE,

    -- Source event identity.
    event_id                varchar(100) NOT NULL,
    event_type              varchar(100) NOT NULL,

    -- The exact bytes sent (jsonb for querying; the wire format is JSON).
    payload                 jsonb NOT NULL,
    signature               varchar(255) NOT NULL,

    -- Attempt-specific metadata.
    attempt_number          integer NOT NULL DEFAULT 1,
    status                  varchar(30) NOT NULL,
    response_status         integer,
    response_body           text,
    error_message           text,

    scheduled_at            timestamptz NOT NULL DEFAULT now(),
    started_at              timestamptz,
    completed_at            timestamptz,

    CONSTRAINT chk_delivery_attempt_status
        CHECK (status IN ('PENDING',
                          'SUCCESS',
                          'FAILED_RETRYABLE',
                          'FAILED_PERMANENT')),

    CONSTRAINT chk_delivery_attempt_number
        CHECK (attempt_number > 0)
);

COMMENT ON TABLE notifications.webhook_delivery_attempt IS
    'Audit log of webhook delivery attempts. One row per HTTP attempt.';
COMMENT ON COLUMN notifications.webhook_delivery_attempt.delivery_id IS
    'Stable across retries. Sent to the subscriber as X-Webhook-Delivery-Id.';

-- Owner views all deliveries for one subscription, newest first.
CREATE INDEX idx_delivery_attempt_subscription
    ON notifications.webhook_delivery_attempt(subscription_id, scheduled_at DESC);

-- Lookup all attempts for a given logical delivery.
CREATE INDEX idx_delivery_attempt_delivery
    ON notifications.webhook_delivery_attempt(delivery_id);

-- Partial index for pending attempts. Useful for a future reconciliation job.
CREATE INDEX idx_delivery_attempt_status
    ON notifications.webhook_delivery_attempt(status)
    WHERE status = 'PENDING';

-- ==============================================================================
-- Table: webhook_delivery_dlq
-- ------------------------------------------------------------------------------
-- Deliveries that exhausted retries or hit a permanent failure. Kept
-- separately from the attempt table so operators can query the DLQ without