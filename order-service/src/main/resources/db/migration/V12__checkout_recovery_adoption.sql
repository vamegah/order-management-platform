-- A manual admission is visible to recovery only with its committed audit.
CREATE TABLE orders.checkout_recovery_adoption (
    order_id uuid PRIMARY KEY REFERENCES orders.checkout_attempt(order_id),
    actor varchar(200) NOT NULL CHECK (length(trim(actor)) > 0),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    requested_at timestamptz NOT NULL DEFAULT now()
);
