ALTER TABLE payments.payment
    ADD COLUMN provider varchar(30),
    ADD COLUMN failure_code varchar(50),
    ADD COLUMN failure_message varchar(500),
    ADD COLUMN authorized_at timestamptz,
    ADD COLUMN captured_at timestamptz,
    ADD COLUMN failed_at timestamptz,
    ADD COLUMN refunded_at timestamptz;
CREATE INDEX idx_payment_provider_reference ON payments.payment(provider, provider_reference);
ALTER TABLE payments.refund
    ADD COLUMN provider_reference varchar(255),
    ADD COLUMN completed_at timestamptz;
CREATE TABLE payments.provider_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(255) NOT NULL,
    provider_event_id varchar(255) NOT NULL,
    event_type varchar(255) NOT NULL,
    payload text NOT NULL,
    status varchar(255) NOT NULL DEFAULT 'RECEIVED',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(provider, provider_event_id)
);
