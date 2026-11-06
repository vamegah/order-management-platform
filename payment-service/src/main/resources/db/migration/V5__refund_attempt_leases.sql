-- Refund intent commits before contacting the provider. Leases fence late replies.
ALTER TABLE payments.refund ADD COLUMN attempt_id uuid;
ALTER TABLE payments.refund ADD COLUMN attempt_until timestamptz;
-- NULL identifies legacy intents whose provider-side idempotency cannot be assumed.
ALTER TABLE payments.refund ADD COLUMN provider_key varchar(255);
CREATE UNIQUE INDEX ux_refund_provider_key ON payments.refund (provider_key)
    WHERE provider_key IS NOT NULL;
ALTER TABLE payments.refund ADD CONSTRAINT refund_attempt_pair
    CHECK ((attempt_id IS NULL) = (attempt_until IS NULL));
CREATE INDEX idx_refund_pending_payment ON payments.refund (payment_id)
    WHERE status = 'PENDING';
