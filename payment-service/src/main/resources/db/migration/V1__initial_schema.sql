-- ==============================================================================
-- Payment Service — Initial Schema
-- ------------------------------------------------------------------------------
-- Flyway migration V1. Creates:
--   - payments.payment
--   - payments.refund
--
-- The `payments` schema and `payments_svc` role are created by the platform's
-- shared initialization (Phase 0, File 6). This migration only creates tables.
-- ==============================================================================

-- ==============================================================================
-- Table: payment
-- ==============================================================================
CREATE TABLE payments.payment (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Cross-service references (no FK).
    order_id            uuid NOT NULL,
    customer_id         uuid NOT NULL,

    -- Amount as (numeric, char(3)) — same representation as Phase 1's Money.
    amount              numeric(19,4) NOT NULL,
    currency            char(3) NOT NULL,

    -- Denormalized sum of refunds. Null when no refunds.
    refunded_amount     numeric(19,4),
    refunded_currency   char(3),

    status              varchar(30) NOT NULL DEFAULT 'PENDING',

    -- Set on confirmation. Provider-specific (e.g., Stripe's pi_...).
    provider_reference  varchar(255),

    -- Idempotency key for CreatePaymentIntent. Unique when present.
    idempotency_key     varchar(255) UNIQUE,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_payment_status CHECK (status IN (
        'PENDING', 'CONFIRMED', 'CANCELLED', 'FAILED',
        'REFUNDED', 'PARTIALLY_REFUNDED'
    )),

    CONSTRAINT chk_payment_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT chk_payment_amount_positive CHECK (amount > 0),

    CONSTRAINT chk_payment_refunded_non_negative
        CHECK (refunded_amount IS NULL OR refunded_amount >= 0),

    CONSTRAINT chk_payment_refunded_not_exceeding
        CHECK (refunded_amount IS NULL OR refunded_amount <= amount),

    -- If refunded_amount is set, refunded_currency must match the payment currency.
    CONSTRAINT chk_payment_refunded_currency_matches
        CHECK (refunded_amount IS NULL OR refunded_currency = currency)
);

COMMENT ON TABLE payments.payment IS
    'Payment intents and their lifecycle. Idempotency keys dedupe create requests.';
COMMENT ON COLUMN payments.payment.provider_reference IS
    'Set on confirmation; provider-specific. Example: Stripe pi_xxx.';
COMMENT ON COLUMN payments.payment.idempotency_key IS
    'Unique when present. Retries with the same key return the existing payment.';

CREATE INDEX idx_payment_order ON payments.payment(order_id);
CREATE INDEX idx_payment_customer ON payments.payment(customer_id);
CREATE INDEX idx_payment_status_created ON payments.payment(status, created_at DESC);

-- ==============================================================================
-- Table: refund
-- ==============================================================================
CREATE TABLE payments.refund (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    payment_id          uuid NOT NULL REFERENCES payments.payment(id)
                            ON DELETE RESTRICT,

    amount              numeric(19,4) NOT NULL,
    currency            char(3) NOT NULL,

    status              varchar(20) NOT NULL DEFAULT 'PENDING',
    reason              varchar(500),

    idempotency_key     varchar(255) UNIQUE,

    created_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_refund_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_refund_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE payments.refund IS
    'Refunds applied to payments. Multiple partial refunds per payment are allowed.';

CREATE INDEX idx_refund_payment ON payments.refund(payment_id);

ALTER TABLE payments.payment
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;