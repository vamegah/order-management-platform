-- Capture eligibility once at upgrade. No reference or business state is repaired here.
CREATE TABLE orders.legacy_reservation_reference_line (
    line_id uuid PRIMARY KEY REFERENCES orders.order_line(id),
    order_id uuid NOT NULL REFERENCES orders.purchase_order(id),
    product_id uuid NOT NULL,
    sku varchar(64) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    original_status varchar(20) NOT NULL,
    original_order_version bigint NOT NULL CHECK (original_order_version >= 0),
    detected_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (line_id, order_id)
);

INSERT INTO orders.legacy_reservation_reference_line
    (line_id, order_id, product_id, sku, quantity, original_status, original_order_version)
SELECT l.id, l.order_id, l.product_id, l.product_sku, l.quantity, o.status, o.version
FROM orders.order_line l JOIN orders.purchase_order o ON o.id = l.order_id
WHERE l.reservation_id IS NULL;

CREATE TABLE orders.terminal_reservation_reference_disposition (
    line_id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    id uuid NOT NULL UNIQUE,
    policy varchar(64) NOT NULL CHECK (policy = 'IRRECOVERABLE_LEGACY_REFERENCE'),
    previous_order_version bigint NOT NULL CHECK (previous_order_version >= 0),
    actor varchar(200) NOT NULL CHECK (length(trim(actor)) > 0),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    evidence varchar(2000) NOT NULL CHECK (length(trim(evidence)) > 0),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (line_id, order_id)
        REFERENCES orders.legacy_reservation_reference_line(line_id, order_id)
);
CREATE INDEX idx_terminal_reference_disposition_order
    ON orders.terminal_reservation_reference_disposition(order_id, line_id);
