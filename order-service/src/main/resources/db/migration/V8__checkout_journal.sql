CREATE TABLE orders.checkout_attempt (
    order_id uuid PRIMARY KEY,
    status varchar(20) NOT NULL CHECK (status IN ('STARTED', 'FAILED', 'ACCEPTED', 'RECOVERING', 'RECOVERED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    recover_after timestamptz NOT NULL DEFAULT now() + interval '10 minutes',
    lease_id uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    last_error varchar(200)
);
CREATE INDEX idx_checkout_recovery ON orders.checkout_attempt (recover_after) WHERE status <> 'ACCEPTED';
