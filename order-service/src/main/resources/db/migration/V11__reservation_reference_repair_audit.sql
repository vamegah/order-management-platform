-- Repair existing references only; historical rows are not rewritten by this migration.
CREATE TABLE orders.reservation_reference_repair (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders.purchase_order(id),
    line_id uuid NOT NULL UNIQUE REFERENCES orders.order_line(id),
    reservation_id uuid NOT NULL UNIQUE,
    previous_order_version bigint NOT NULL CHECK (previous_order_version >= 0),
    actor varchar(200) NOT NULL CHECK (length(trim(actor)) > 0),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    repaired_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservation_reference_repair_order
    ON orders.reservation_reference_repair(order_id, repaired_at, id);
