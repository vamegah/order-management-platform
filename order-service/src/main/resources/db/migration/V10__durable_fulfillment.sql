ALTER TABLE orders.purchase_order DROP CONSTRAINT order_status_valid;
ALTER TABLE orders.purchase_order ADD CONSTRAINT order_status_valid
    CHECK (status IN ('DRAFT','PLACED','CONFIRMED','FULFILLING','SHIPPED','DELIVERED','CANCELLED'));
ALTER TABLE orders.order_status_history DROP CONSTRAINT order_status_history_from_valid;
ALTER TABLE orders.order_status_history ADD CONSTRAINT order_status_history_from_valid
    CHECK (from_status IS NULL OR from_status IN
        ('DRAFT','PLACED','CONFIRMED','FULFILLING','SHIPPED','DELIVERED','CANCELLED'));
ALTER TABLE orders.order_status_history DROP CONSTRAINT order_status_history_to_valid;
ALTER TABLE orders.order_status_history ADD CONSTRAINT order_status_history_to_valid
    CHECK (to_status IN ('DRAFT','PLACED','CONFIRMED','FULFILLING','SHIPPED','DELIVERED','CANCELLED'));

-- Intent and tasks join the aggregate transaction. No bearer credentials are stored.
CREATE TABLE orders.fulfillment_request (
    order_id uuid PRIMARY KEY REFERENCES orders.purchase_order(id),
    requested_by varchar(100) NOT NULL,
    reason varchar(500),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE INDEX idx_fulfillment_request_due ON orders.fulfillment_request(next_attempt_at, order_id)
    WHERE completed_at IS NULL;

CREATE TABLE orders.fulfillment_task (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders.fulfillment_request(order_id),
    reservation_id uuid NOT NULL UNIQUE,
    sku varchar(64) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    completed boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    last_error varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX idx_fulfillment_task_due ON orders.fulfillment_task(order_id, next_attempt_at, id)
    WHERE NOT completed;
