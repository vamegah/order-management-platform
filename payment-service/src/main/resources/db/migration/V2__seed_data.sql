-- ==============================================================================
-- Payment Service — Seed Data
-- ------------------------------------------------------------------------------
-- Flyway migration V2. Dev/test seed data.
--
-- The order IDs match Phase 1's order seed data (order-service V2):
--   - Order 1: DELIVERED (Alice) — has a confirmed + refunded payment
--   - Order 2: CONFIRMED (Bob)   — has a confirmed payment
--   - Order 3: DRAFT (Alice)     — no payment (DRAFT orders have no payment)
--
-- UUID CONVENTION
--   Payments:  00000000-0000-0000-0000-0000000008XX
--   Refunds:   00000000-0000-0000-0000-0000000009XX
-- ==============================================================================

INSERT INTO payments.payment
    (id, order_id, customer_id, amount, currency, refunded_amount, refunded_currency,
     status, provider_reference, idempotency_key, created_at, updated_at)
VALUES
    -- Payment for Order 1 (DELIVERED, total $1402.92)
    -- Confirmed, then fully refunded.
    ('00000000-0000-0000-0000-000000000801',
     '00000000-0000-0000-0000-000000000301',   -- Order 1
     '00000000-0000-0000-0000-000000000101',   -- Alice
     1402.9200, 'USD', 1402.9200, 'USD',
     'REFUNDED',
     'pi_test_order301',
     'seed-order-301',
     '2025-01-08 14:23:11+00', '2025-01-11 10:00:00+00'),

    -- Payment for Order 2 (CONFIRMED, total $1088.91)
    ('00000000-0000-0000-0000-000000000802',
     '00000000-0000-0000-0000-000000000302',   -- Order 2
     '00000000-0000-0000-0000-000000000102',   -- Bob
     1088.9100, 'USD', NULL, NULL,
     'CONFIRMED',
     'pi_test_order302',
     'seed-order-302',
     '2025-01-14 09:15:30+00', '2025-01-14 09:15:30+00')

    -- Order 3 is DRAFT — no payment.
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.refund
    (id, payment_id, amount, currency, status, reason, idempotency_key, created_at)
VALUES
    -- Full refund of the order 1 payment.
    ('00000000-0000-0000-0000-000000000901',
     '00000000-0000-0000-0000-000000000801',
     1402.9200, 'USD', 'COMPLETED',
     'Customer returned items',
     'seed-refund-301',
     '2025-01-11 09:55:00+00')
ON CONFLICT (id) DO NOTHING;

-- V2__grants.sql
-- Grants for the payments_svc role on the payments schema.
-- Safe to run multiple times.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payments_svc') THEN
        GRANT USAGE ON SCHEMA payments TO payments_svc;
        GRANT CREATE ON SCHEMA payments TO payments_svc;
        GRANT ALL ON ALL TABLES IN SCHEMA payments TO payments_svc;
        GRANT ALL ON ALL SEQUENCES IN SCHEMA payments TO payments_svc;

        ALTER DEFAULT PRIVILEGES IN SCHEMA payments
            GRANT ALL ON TABLES TO payments_svc;
        ALTER DEFAULT PRIVILEGES IN SCHEMA payments
            GRANT ALL ON SEQUENCES TO payments_svc;

        ALTER ROLE payments_svc SET search_path TO payments, public;
    END IF;
END
$$;