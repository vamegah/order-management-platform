CREATE TABLE orders.cleanup_task (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    resource_type varchar(20) NOT NULL CHECK (resource_type IN ('RESERVATION', 'PAYMENT')),
    resource_id uuid NOT NULL,
    completed boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    last_error varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE (resource_type, resource_id)
);
CREATE INDEX idx_cleanup_due ON orders.cleanup_task (next_attempt_at, created_at) WHERE NOT completed;
