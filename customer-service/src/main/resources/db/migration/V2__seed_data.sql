-- ==============================================================================
-- Customer Service — Seed Data
-- ------------------------------------------------------------------------------
-- Flyway migration V2. Development and test data.
--
-- UUID CONVENTION
--   Customers:  00000000-0000-0000-0000-0000000001XX
--   Addresses:  00000000-0000-0000-0000-0000000002XX
-- ==============================================================================

-- ==============================================================================
-- Customers
-- ==============================================================================
INSERT INTO customers.customer
    (id, email, keycloak_sub, first_name, last_name, phone, date_of_birth, status)
VALUES
    -- Alice Customer: the "happy path" test user, linked to Keycloak.
    ('00000000-0000-0000-0000-000000000101',
     'alice@omp.local',
     'test-alice',     -- matches Keycloak test user 'alice' sub
     'Alice',
     'Customer',
     '+1-555-0101',
     '1990-05-15',
     'ACTIVE'),

    ('00000000-0000-0000-0000-000000000102',
     'bob@omp.local',
     NULL,
     'Bob',
     'Buyer',
     '+1-555-0102',
     '1985-11-02',
     'ACTIVE'),

    ('00000000-0000-0000-0000-000000000103',
     'carol@omp.local',
     NULL,
     'Carol',
     'Client',
     '+49-30-1234567',
     NULL,
     'ACTIVE'),

    ('00000000-0000-0000-0000-000000000104',
     'dave@omp.local',
     NULL,
     'Dave',
     'Dormant',
     NULL,
     '1975-03-22',
     'INACTIVE'),

    ('00000000-0000-0000-0000-000000000105',
     'eve@omp.local',
     NULL,
     'Eve',
     'Suspended',
     '+1-555-0105',
     NULL,
     'SUSPENDED')
ON CONFLICT (id) DO NOTHING;

-- ==============================================================================
-- Addresses
-- ==============================================================================
INSERT INTO customers.address
    (id, customer_id, address_type, line1, line2, city, state, postal_code, country, is_default)
VALUES
    -- Alice: billing + shipping (different addresses)
    ('00000000-0000-0000-0000-000000000201',
     '00000000-0000-0000-0000-000000000101',
     'BILLING',
     '100 Billing St',
     'Apt 4A',
     'San Francisco',
     'CA',
     '94103',
     'US',
     true),

    ('00000000-0000-0000-0000-000000000202',
     '00000000-0000-0000-0000-000000000101',
     'SHIPPING',
     '200 Shipping Ave',
     NULL,
     'Oakland',
     'CA',
     '94607',
     'US',
     true),

    -- Bob: one BOTH address
    ('00000000-0000-0000-0000-000000000203',
     '00000000-0000-0000-0000-000000000102',
     'BOTH',
     '300 Both Blvd',
     'Suite 200',
     'New York',
     'NY',
     '10001',
     'US',
     true),

    -- Carol: German address
    ('00000000-0000-0000-0000-000000000204',
     '00000000-0000-0000-0000-000000000103',
     'BOTH',
     'Hauptstraße 42',
     NULL,
     'Berlin',
     NULL,
     '10115',
     'DE',
     true),

    -- Dave: has an address even though INACTIVE (historical)
    ('00000000-0000-0000-0000-000000000205',
     '00000000-0000-0000-0000-000000000104',
     'BOTH',
     '400 Old Rd',
     NULL,
     'Chicago',
     'IL',
     '60601',
     'US',
     true)

    -- Eve: no addresses. Tests the "customer without address" edge case.
ON CONFLICT (id) DO NOTHING;

-- ==============================================================================
-- Preferences
-- ==============================================================================
INSERT INTO customers.customer_preferences
    (customer_id, locale, currency, marketing_opt_in, notification_email, notification_sms)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'en-US', 'USD', true,  true,  false),
    ('00000000-0000-0000-0000-000000000102', 'en-US', 'USD', false, true,  false),
    ('00000000-0000-0000-0000-000000000103', 'de-DE', 'EUR', true,  true,  true),
    ('00000000-0000-0000-0000-000000000104', 'en-US', 'USD', false, false, false),
    ('00000000-0000-0000-0000-000000000105', 'en-US', 'USD', false, false, false)
ON CONFLICT (customer_id) DO NOTHING;