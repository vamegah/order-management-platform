ALTER TABLE payments.refund
    ADD COLUMN recovery_attempts integer NOT NULL DEFAULT 0 CHECK (recovery_attempts >= 0),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_error varchar(64),
    ADD COLUMN attention_reason varchar(64);

UPDATE payments.refund SET attention_reason = 'LEGACY_INTENT'
WHERE status = 'PENDING' AND provider_key IS NULL;

CREATE INDEX idx_refund_recovery_due ON payments.refund (next_attempt_at, id)
    WHERE status = 'PENDING' AND attention_reason IS NULL;

CREATE INDEX idx_refund_attention ON payments.refund (id)
    WHERE status = 'PENDING' AND attention_reason IS NOT NULL;
